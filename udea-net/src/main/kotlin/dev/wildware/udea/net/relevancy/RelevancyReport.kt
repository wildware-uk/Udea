package dev.wildware.udea.net.relevancy

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.transport.PeerId

/**
 * What the fog solve cost and what it did, counted rather than timed.
 *
 * ## Counted rather than timed, on purpose
 *
 * Spec 7 asks for the solve to be budgeted and instrumented from the first commit. The obvious
 * instrument is `System.nanoTime`, and it is the wrong one twice over: standards §4 forbids the
 * wall clock inside simulation code outright, and a nanosecond budget is a different number on a
 * developer laptop, a CI runner and a server, so a threshold that holds anywhere holds nowhere.
 * [distanceTests] and [cellVisits] are what the solve actually spends. They are identical on
 * every machine, they move the moment somebody reintroduces an all-pairs loop, and a CI test can
 * assert an exact ceiling on them.
 *
 * ## Why the team/client split is counted separately
 *
 * [teamSolves] against [clientQueries] is the evidence for spec 7's 5× claim. Without both
 * numbers, "we compute vision per team" is a comment; with them, a 5v5 tick visibly reads two
 * solves and ten times as many bitset queries, and a regression that quietly reintroduces
 * per-client solving shows up as [teamSolves] tracking the client count.
 */
public class RelevancyStats {

    /** Completed [FogOfWar.endSolve] calls. */
    public var solves: Long = 0L
        internal set

    /** Per-team vision passes. This is `solves * teams`, and it is the number spec 7 caps. */
    public var teamSolves: Long = 0L
        internal set

    /** Entities offered to [FogOfWar.observe], summed over solves. */
    public var entitiesObserved: Long = 0L
        internal set

    /** Vision sources bucketed into the grid, summed over solves. */
    public var sourcesIndexed: Long = 0L
        internal set

    /** Grid cells walked while probing. The grid's own overhead. */
    public var cellVisits: Long = 0L
        internal set

    /** Entity-to-source distance tests. The solve's dominant term and its budget. */
    public var distanceTests: Long = 0L
        internal set

    /** Solves whose distance tests exceeded [FogSettings.distanceTestBudget]. */
    public var overBudgetSolves: Long = 0L
        internal set

    /** Entities that became visible to some team. */
    public var entered: Long = 0L
        internal set

    /** Entities that stopped being visible to some team while still existing. */
    public var left: Long = 0L
        internal set

    /** `RelevancySet.isRelevant` calls: once per entity per client per tick. */
    public var clientQueries: Long = 0L
        internal set

    /** Of those, ones from a client with no team, which are answered "no". */
    public var unassignedQueries: Long = 0L
        internal set

    /** Distance tests per solve, or zero before the first. The number to watch. */
    public val distanceTestsPerSolve: Long get() = if (solves == 0L) 0L else distanceTests / solves

    override fun toString(): String =
        "RelevancyStats($solves solve(s), $teamSolves team pass(es), $distanceTests distance " +
            "test(s), $entered in / $left out, $overBudgetSolves over budget)"
}

/**
 * One entity's relevancy to one client, with the reason it is relevant.
 *
 * Spec 7 asks for `net.relevancy(client)` to return "the granting vision source so flicker is
 * diagnosable in one tool call". [source] is that. The two counters beside it are what turn a
 * single reading into a diagnosis: an entity with [enters] at 40 after 60 ticks is oscillating,
 * and [source] names which vision source keeps letting go of it.
 */
public class RelevancyEntry(

    /** The entity. */
    public val netId: NetId,

    /** Whether this client may be told about it at all. */
    public val visible: Boolean,

    /** Why. [VisionReason.Lingering] means it is inside the anti-flicker grace window. */
    public val reason: VisionReason,

    /** The vision source granting it, or [NetId.NONE]. The entity itself for an ally. */
    public val source: NetId,

    /** Distance to [source], or infinity when nothing grants it. */
    public val distance: Float,

    /** The tick [visible] last changed. */
    public val since: Tick,

    /** How many times it has entered this team's vision. A boundary walk must leave this at one. */
    public val enters: Int,

    /** How many times it has left. Zero is what a correctly hysteresised boundary walk shows. */
    public val leaves: Int,
) {
    override fun toString(): String =
        "RelevancyEntry($netId, visible=$visible, $reason from $source at $distance)"
}

/**
 * One client's whole relevancy set, as `net.relevancy` prints it.
 *
 * Allocates a list, and that is fine because nothing on the tick path calls it: this is the
 * diagnostic read model, built on demand from the per-team bitsets the solve already produced.
 * The solve itself allocates nothing.
 */
public class RelevancyReport(

    /** Who this is about. */
    public val client: PeerId,

    /** The team the client is on, or [FogOfWar.NO_TEAM]. */
    public val team: Int,

    /** The tick the last solve was for. */
    public val tick: Tick,

    /** Everything this client may be told about, in ascending `NetId.index` order. */
    public val entries: List<RelevancyEntry>,

    /** Entities that left this client's view in the last solve, still alive. */
    public val left: List<NetId>,
) {

    /** Whether this client has a team at all. A client with none is shown nothing. */
    public val assigned: Boolean get() = team != FogOfWar.NO_TEAM

    override fun toString(): String =
        "RelevancyReport($client on team $team at ${tick.value}: ${entries.size} visible)"

    public companion object {

        /** Builds [client]'s report from [fog]'s last solve. */
        public fun of(fog: FogOfWar, client: PeerId): RelevancyReport {
            val team = fog.teamOf(client)
            if (team == FogOfWar.NO_TEAM) {
                return RelevancyReport(client, team, fog.lastSolveTick, emptyList(), emptyList())
            }
            val seen = fog.vision[team]
            val entries = ArrayList<RelevancyEntry>(seen.visibleCount)
            for (index in 0 until seen.visibleCount) entries += entryOf(fog, client, seen.visibleAt(index))
            val left = ArrayList<NetId>(seen.leftCount)
            for (index in 0 until seen.leftCount) left += seen.leftAt(index)
            return RelevancyReport(client, team, fog.lastSolveTick, entries, left)
        }

        /**
         * One entity's entry, whether or not it is visible.
         *
         * The hidden case is the one `net.assert_not_visible` needs: the answer to "why can this
         * client not see X" has to exist, or the anti-cheat assertion can only say "no" and an
         * operator is back to inference.
         */
        public fun entryOf(fog: FogOfWar, client: PeerId, netId: NetId): RelevancyEntry {
            val team = fog.teamOf(client)
            if (team == FogOfWar.NO_TEAM) {
                return RelevancyEntry(
                    netId = netId,
                    visible = false,
                    reason = VisionReason.Hidden,
                    source = NetId.NONE,
                    distance = Float.POSITIVE_INFINITY,
                    since = fog.lastSolveTick,
                    enters = 0,
                    leaves = 0,
                )
            }
            val seen = fog.vision[team]
            return RelevancyEntry(
                netId = netId,
                visible = seen.canSee(netId),
                reason = seen.reasonFor(netId),
                source = seen.sourceOf(netId),
                distance = seen.distanceOf(netId),
                since = seen.sinceTick(netId),
                enters = seen.enterCount(netId),
                leaves = seen.leaveCount(netId),
            )
        }
    }
}
