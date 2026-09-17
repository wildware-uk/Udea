package dev.wildware.udea.net.relevancy

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.replication.RelevancySet
import dev.wildware.udea.net.transport.PeerId
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The knobs that decide what fog feels like, kept together so [FogOfWar] does not take nine
 * constructor parameters.
 *
 * Every default here is a flicker decision rather than a taste decision, so the values carry
 * their reasoning: the boundary-walk test fails if [hysteresis] or [lingerTicks] is zeroed.
 */
public class FogSettings(

    /**
     * How far past a source's radius an *already visible* entity stays visible, as a fraction.
     *
     * `0.1` means sight is gained at `R` and lost at `1.1R`. This is the spatial half of the
     * anti-flicker story: without a band, a body walking along the rim of a circle crosses the
     * threshold on every float wobble and generates a Leave/Create pair per crossing. With one,
     * it has to actually walk 10% further out before anything is sent.
     */
    public val hysteresis: Float = DEFAULT_HYSTERESIS,

    /**
     * Ticks an entity stays visible after the last source stopped covering it.
     *
     * The temporal half. A band does nothing about a *source* that blinks — dies, teleports, or
     * simply is not offered for one tick while a system reorders — and a one-tick gap in
     * coverage would otherwise cost a Leave and a full re-Create. Six ticks is a tenth of a
     * second at 60Hz: below human perception, and far longer than any single-tick gap.
     */
    public val lingerTicks: Int = DEFAULT_LINGER_TICKS,

    /**
     * Distance at which an entity's replication weight has halved.
     *
     * Feeds `RelevancySet.weightOf`, which multiplies the priority accumulator: a hero being
     * fought grows priority fast, a minion at the edge of vision grows it slowly and is
     * therefore updated rarely, but never starved, because its priority still climbs.
     */
    public val weightFalloff: Float = DEFAULT_WEIGHT_FALLOFF,

    /** Floor on that weight, so a distant entity is deprioritised and never starved. */
    public val minWeight: Float = DEFAULT_MIN_WEIGHT,

    /**
     * Distance tests one solve may spend before [RelevancyStats.overBudgetSolves] ticks up.
     *
     * A counter and not a throw. Spec 7 asks for the solve to be "budgeted and instrumented from
     * the first commit", and the honest instrument is a *work* count rather than a wall-clock
     * reading: simulation code may not call `System.nanoTime` (standards §4), and a nanosecond
     * budget would be a different number on every machine and every CI runner. Distance tests
     * are what the solve actually spends, they are deterministic, and a regression in them is a
     * regression in cost on every machine at once.
     */
    public val distanceTestBudget: Long = DEFAULT_DISTANCE_TEST_BUDGET,
) {

    init {
        require(hysteresis >= 0f) { "hysteresis is a fraction of the sight radius, was $hysteresis" }
        require(lingerTicks >= 0) { "lingerTicks must be non-negative, was $lingerTicks" }
        require(weightFalloff > 0f) { "weightFalloff must be positive, was $weightFalloff" }
        require(minWeight in 0f..1f) { "minWeight is a multiplier in 0..1, was $minWeight" }
        require(distanceTestBudget > 0L) { "distanceTestBudget must be positive, was $distanceTestBudget" }
    }

    public companion object {

        /** Ten percent of the sight radius. Wide enough to swallow float wobble at the rim. */
        public const val DEFAULT_HYSTERESIS: Float = 0.1f

        /** A tenth of a second at 60Hz. */
        public const val DEFAULT_LINGER_TICKS: Int = 6

        /** Half weight at ten world units, which is roughly a screen edge in the arena. */
        public const val DEFAULT_WEIGHT_FALLOFF: Float = 10f

        /** A twentieth. A far entity updates rarely and provably not never. */
        public const val DEFAULT_MIN_WEIGHT: Float = 0.05f

        /** Comfortably above a 5v5 lane's measured cost, low enough to catch a quadratic. */
        public const val DEFAULT_DISTANCE_TEST_BUDGET: Long = 20_000L
    }
}

/**
 * Per-team fog of war as a `RelevancySet`: the server never serialises what a client cannot see.
 *
 * ## This is anti-cheat, not rendering
 *
 * Spec 3 puts fog in replication rather than presentation for one reason: hiding an entity
 * client-side leaves its position in the client's memory, and a maphack is then a UI patch.
 * Here the entity never reaches the packer at all — `ReplicationServer.accumulateAndSelect`
 * skips anything [isRelevant] refuses — so there is nothing on the wire for a sniffer to find.
 * `AntiCheatWireTest` asserts exactly that, at the byte level, against a real datagram.
 *
 * ## Per team, and what that saves
 *
 * Spec 7's named mitigation. Vision is solved once per team, so a 5v5 does two solves rather
 * than ten; [isRelevant] is then a bitset test behind a client-to-team lookup, which is what
 * makes the per-client half free. [RelevancyStats.teamSolves] and [RelevancyStats.clientQueries]
 * are reported separately so that ratio is visible rather than assumed.
 *
 * ## The tick shape
 *
 * ```
 * fog.beginSolve(tick)
 * for each replicated entity: fog.observe(netId, x, y, team, sightRadius)
 * fog.endSolve()
 * server.broadcast(snapshot)
 * ```
 *
 * [observe] takes loose floats rather than a component, deliberately: `udea-net` must not know
 * what a game calls its position component, and the alternative — an interface the game
 * implements — would put a virtual call on the hottest loop in the solve to save a game three
 * lines. A game with no vision component passes `sightRadius = 0`, and that entity is a body
 * that can be seen and sees nothing.
 *
 * ## Fail closed
 *
 * An entity nobody offered this tick is not relevant to anybody, and a client with no team is
 * shown nothing. Both are the safe direction for a mechanism whose failure mode is leaking
 * state: a bug becomes an invisible unit, which is loud, rather than a visible one, which is a
 * silent maphack. [RelevancyStats.unassignedQueries] is non-zero when the second case is
 * happening, so "everything is invisible" is one tool call from its cause.
 */
public class FogOfWar(

    /** The spatial index this tick's vision sources are bucketed into. */
    public val grid: VisionGrid,

    /** How many teams exist. Each gets one solve and one [TeamVision]. */
    public val teams: Int,

    /** Hysteresis, linger, weighting and the cost budget. */
    public val settings: FogSettings = FogSettings(),

    /** How many `NetId` indices each team tracks. */
    public val capacity: Int = DEFAULT_CAPACITY,
) : RelevancySet {

    init {
        require(teams in 1..MAX_TEAMS) { "teams must be 1..$MAX_TEAMS, was $teams" }
    }

    /** Per-team vision, indexed by team id. */
    public val vision: List<TeamVision> = (0 until teams).map { TeamVision(it, capacity) }

    /** Solve cost and transition counters. Read by `net.relevancy`; never reset by the solve. */
    public val stats: RelevancyStats = RelevancyStats()

    private var clientTeams = IntArray(INITIAL_CLIENTS) { NO_TEAM }

    private var entityRaw = IntArray(INITIAL_ENTITIES)
    private var entityX = FloatArray(INITIAL_ENTITIES)
    private var entityY = FloatArray(INITIAL_ENTITIES)
    private var entityTeam = IntArray(INITIAL_ENTITIES)
    private var entitySight = FloatArray(INITIAL_ENTITIES)
    private var entityCount = 0
    private var solveTick = Tick.ZERO
    private var solving = false

    /** The tick the last completed solve was for. */
    public val lastSolveTick: Tick get() = solveTick

    /** Puts [client] on [team]. A client with no team is shown nothing. */
    public fun assign(client: PeerId, team: Int) {
        require(team in 0 until teams) { "$client cannot be on team $team; this session has $teams" }
        if (client.raw >= clientTeams.size) {
            val grown = IntArray(max(client.raw + 1, clientTeams.size * 2)) { NO_TEAM }
            clientTeams.copyInto(grown)
            clientTeams = grown
        }
        clientTeams[client.raw] = team
    }

    /** [client]'s team, or -1 when it has none. */
    public fun teamOf(client: PeerId): Int =
        if (client.raw >= clientTeams.size) NO_TEAM else clientTeams[client.raw]

    /** Opens the solve for [tick]. Every entity must then be offered to [observe]. */
    public fun beginSolve(tick: Tick) {
        check(!solving) { "a solve for $solveTick is already open; call endSolve() first" }
        require(tick >= solveTick) { "fog solves run forward: $tick is before $solveTick" }
        solving = true
        solveTick = tick
        entityCount = 0
    }

    /**
     * Offers one replicated entity to this tick's solve.
     *
     * @param team which team owns it. Its own team always sees it.
     * @param sightRadius how far it grants vision to its own team; zero for a body that sees
     *   nothing, which is the right answer for a projectile, a ward's target or a corpse.
     */
    public fun observe(netId: NetId, x: Float, y: Float, team: Int, sightRadius: Float) {
        check(solving) { "no solve is open; call beginSolve(tick) first" }
        require(team in 0 until teams) { "$netId is on team $team; this session has $teams" }
        require(!netId.isNone) { "NetId.NONE names no entity and cannot be observed" }
        if (entityCount == entityRaw.size) grow()
        entityRaw[entityCount] = netId.raw
        entityX[entityCount] = x
        entityY[entityCount] = y
        entityTeam[entityCount] = team
        entitySight[entityCount] = sightRadius
        entityCount++
    }

    /** Solves every team's vision from what was offered, and publishes it. */
    public fun endSolve() {
        check(solving) { "no solve is open" }
        solving = false
        buildGrid()
        val before = stats.distanceTests
        for (team in 0 until teams) solveTeam(team)
        stats.solves++
        stats.entitiesObserved += entityCount.toLong()
        if (stats.distanceTests - before > settings.distanceTestBudget) stats.overBudgetSolves++
    }

    // --- RelevancySet -------------------------------------------------------------------------

    /**
     * Whether [client] may be told about [netId] at all.
     *
     * A bitset test behind one array read. This runs once per entity per client per tick inside
     * `ReplicationServer.accumulateAndSelect`, so it does the least work of anything here — all
     * of the thinking already happened in [endSolve], once per team.
     */
    override fun isRelevant(client: PeerId, netId: NetId): Boolean {
        stats.clientQueries++
        val team = teamOf(client)
        if (team == NO_TEAM) {
            stats.unassignedQueries++
            return false
        }
        return vision[team].canSee(netId)
    }

    /** How much [netId] matters to [client]: full weight for an ally, falling off with distance. */
    override fun weightOf(client: PeerId, netId: NetId): Float {
        val team = teamOf(client)
        if (team == NO_TEAM) return settings.minWeight
        val distance = vision[team].distanceOf(netId)
        if (!distance.isFinite()) return settings.minWeight
        val weight = 1f / (1f + distance / settings.weightFalloff)
        return if (weight < settings.minWeight) settings.minWeight else weight
    }

    // --- diagnosis ----------------------------------------------------------------------------

    /** Entities that left [client]'s view in the last solve **without** ceasing to exist. */
    public fun leftCount(client: PeerId): Int {
        val team = teamOf(client)
        return if (team == NO_TEAM) 0 else vision[team].leftCount
    }

    /** The [index]th such entity. Feeds `EntityOp.Leave`; never `EntityOp.Destroy`. */
    public fun leftAt(client: PeerId, index: Int): NetId {
        val team = teamOf(client)
        require(team != NO_TEAM) { "$client has no team, so nothing ever leaves its view" }
        return vision[team].leftAt(index)
    }

    override fun toString(): String =
        "FogOfWar($teams team(s), tick=${solveTick.value}, ${stats.solves} solve(s))"

    /**
     * Buckets **every** entity, and deliberately not only the vision sources.
     *
     * Which way round the grid goes is the difference between the fog solve being cheap and
     * being a pessimisation, so it is worth stating. Indexing the *sources* and asking each
     * entity "which sources are near me" costs `entities x cells(sightRadius)` cell walks —
     * 250 bodies each walking a 7x7 neighbourhood is 12 000 cell visits a team, which is worse
     * than the all-pairs loop it replaces. Indexing the *entities* and letting each source stamp
     * its own neighbourhood costs `sources x cells(sightRadius)`, and there are an order of
     * magnitude fewer sources than bodies in every game this is for. The measured numbers are
     * asserted in `RelevancyBudgetTest`, precisely so this cannot silently invert again.
     */
    private fun buildGrid() {
        grid.clear()
        for (slot in 0 until entityCount) grid.add(slot, entityX[slot], entityY[slot])
        grid.build()
    }

    private fun solveTeam(team: Int) {
        val seen = vision[team]
        seen.beginSolve(solveTick.value)
        for (slot in 0 until entityCount) {
            val netId = NetId.ofRaw(entityRaw[slot])
            seen.observe(netId)
            if (entityTeam[slot] == team) seen.grant(netId, netId, 0f, VisionReason.OwnTeam)
        }
        for (slot in 0 until entityCount) {
            if (entityTeam[slot] != team || entitySight[slot] <= 0f) continue
            stats.sourcesIndexed++
            stamp(seen, team, slot)
        }
        seen.publish(solveTick, settings.lingerTicks)
        stats.teamSolves++
        stats.entered += seen.enteredCount.toLong()
        stats.left += seen.leftCount.toLong()
    }

    /**
     * Grants vision from source [slot] to everything its radius covers.
     *
     * The hysteresis band is applied per candidate and keyed on whether that candidate is
     * *currently* visible — the front buffer, and therefore last tick's published answer. That is
     * the whole of the spatial anti-flicker: the threshold an entity must cross to disappear is
     * strictly further out than the one it crossed to appear, so a body can only oscillate by
     * genuinely travelling the width of the band and back. The cell sweep uses the wide radius
     * unconditionally, because a candidate inside the band must be *reached* before it can be
     * tested.
     */
    private fun stamp(seen: TeamVision, team: Int, slot: Int) {
        val sight = entitySight[slot]
        val wide = sight * (1f + settings.hysteresis)
        val x = entityX[slot]
        val y = entityY[slot]
        val source = NetId.ofRaw(entityRaw[slot])
        for (row in grid.minRow(y, wide)..grid.maxRow(y, wide)) {
            for (column in grid.minColumn(x, wide)..grid.maxColumn(x, wide)) {
                val cell = grid.cellAt(column, row)
                stats.cellVisits++
                for (entry in grid.cellStart(cell) until grid.cellEnd(cell)) {
                    val candidate = grid.slotAt(entry)
                    if (entityTeam[candidate] == team) continue
                    stats.distanceTests++
                    val dx = entityX[candidate] - x
                    val dy = entityY[candidate] - y
                    val squared = dx * dx + dy * dy
                    val netId = NetId.ofRaw(entityRaw[candidate])
                    val radius = if (seen.canSee(netId)) wide else sight
                    if (squared > radius * radius) continue
                    seen.grant(netId, source, sqrt(squared), VisionReason.Sighted)
                }
            }
        }
    }

    private fun grow() {
        val size = entityRaw.size * 2
        entityRaw = entityRaw.copyOf(size)
        entityX = entityX.copyOf(size)
        entityY = entityY.copyOf(size)
        entityTeam = entityTeam.copyOf(size)
        entitySight = entitySight.copyOf(size)
    }

    public companion object {

        /** A client that has not been assigned a team. It is shown nothing. */
        public const val NO_TEAM: Int = -1

        /** Two sides plus a neutral team for jungle and creeps is the shape this is sized for. */
        public const val MAX_TEAMS: Int = 8

        /** `NetId` indices tracked per team. A lane is two hundred bodies; this is slack. */
        public const val DEFAULT_CAPACITY: Int = 4096

        private const val INITIAL_CLIENTS: Int = 16
        private const val INITIAL_ENTITIES: Int = 256
    }
}
