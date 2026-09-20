package dev.wildware.udea.nav

/**
 * The navigation service: the grid the world is standing on, and the routes over it.
 *
 * A game reaches it as `ctx[NavModule.NAVIGATION]` - a `ServiceKey`, not a new field on
 * `GameContext`, which is the route the standards leave open for a module that owns a service the
 * kernel does not name.
 *
 * ## What it holds, and what it refuses to hold
 *
 * The [grid], one [NavPathfinder] over it, and a small cache of [NavFlowField]s. **It holds nothing
 * about any unit.** A unit's route is asked for and answered in the same tick, from the cell it is
 * standing in; nothing here remembers that unit asked. That is what keeps a rewind honest: there is
 * no per-unit state outside the snapshot to be restored, because there is none at all.
 *
 * The caches are pure memoisation: every entry is a value the same call would compute again, and
 * [rebuild] drops all of them with the grid they were computed on, so a cached route cannot outlive
 * the ground it was found on.
 *
 * ## Not thread-safe
 *
 * One service, one simulation, one thread - the same bargain [NavPathfinder] states. A game with
 * two worlds in one process has two services, because each `GameContext` builds its own.
 */
public class Navigation internal constructor(grid: NavGrid) {

    /** The ground as it stands. Replaced wholesale by [rebuild]; never edited. */
    public var grid: NavGrid = grid
        private set

    /** A* over [grid], rebuilt with it. */
    internal var pathfinder: NavPathfinder = NavPathfinder(grid)
        private set

    /** The live flow fields, oldest replaced first. Entries are all for the current [grid]. */
    private val fields = arrayOfNulls<NavFlowField>(FIELD_CACHE_SIZE)

    private var nextField = 0

    /**
     * How many flow fields have been swept since the grid was last rebuilt.
     *
     * `internal` because nothing outside this module has a use for it: it exists so
     * `NavigationCacheTest` can tell a field that was swept from one that came out of the cache,
     * which is the difference a stale cache hides.
     */
    internal var fieldSweeps: Int = 0
        private set

    // The direct-mapped A* hop cache: the key's three parts beside the answer, one slot each.
    // Four `IntArray`s rather than an array of objects, because a cache on a per-tick path that
    // allocated an entry per miss would be an allocation per unit per cell entered.

    private val hopFrom = IntArray(HOP_CACHE_SIZE) { EMPTY_SLOT }

    private val hopGoal = IntArray(HOP_CACHE_SIZE)

    private val hopClearance = IntArray(HOP_CACHE_SIZE)

    private val hopValue = IntArray(HOP_CACHE_SIZE)

    /**
     * Swaps in a grid built from the world's obstacles, and drops every cached route with it.
     *
     * Called by [NavGridSystem] when the footprints in the world stop matching the ones the grid
     * was built from - a building placed, moved or destroyed. Between ticks in effect: the system
     * runs in `PreSimulation`, before anything reads a route this tick.
     */
    internal fun rebuild(grid: NavGrid) {
        this.grid = grid
        pathfinder = NavPathfinder(grid)
        fields.fill(null)
        nextField = 0
        fieldSweeps = 0
        hopFrom.fill(EMPTY_SLOT)
    }

    /**
     * The field of routes to [goal] for a unit needing [clearanceCells] of room, swept if this is
     * the first ask since the grid was rebuilt.
     *
     * The cache is a fixed [FIELD_CACHE_SIZE] entries scanned in order and replaced oldest-first.
     * Fixed rather than unbounded because a field is an `IntArray` the size of the map and a game
     * that gives a hundred separate orders must not accumulate a hundred of them; scanned linearly
     * rather than hashed because the bound is small and a hash map's iteration order is the thing
     * simulation code may not depend on. Which entry is evicted changes nothing anybody can
     * observe: a miss recomputes the same field the hit would have returned.
     */
    public fun flowField(goal: NavCell, clearanceCells: Int): NavFlowField {
        for (index in fields.indices) {
            val held = fields[index] ?: continue
            if (held.goal == goal && held.clearanceCells == clearanceCells) return held
        }
        val swept = NavFlowField(grid, goal, clearanceCells)
        fields[nextField] = swept
        nextField = (nextField + 1) % fields.size
        fieldSweeps++
        return swept
    }

    /**
     * The next cell a unit at [from] should step to on its way to [goal], by A*.
     *
     * What a unit **not** in a group uses, where a group reads a [flowField] instead. The answer
     * is A*'s, and the cache in front of it is direct-mapped and overwritten on collision: the
     * value is a pure function of the key, so a collision costs a search and changes no answer.
     * Without it a single unit would pay a full search on every tick it walks, rather than on
     * every cell it enters.
     */
    public fun nextHop(from: NavCell, goal: NavCell, clearanceCells: Int): NavCell {
        val slot = ((from.index * HASH_ODD + goal.index) * HASH_ODD + clearanceCells) and (HOP_CACHE_SIZE - 1)
        if (hopFrom[slot] == from.index && hopGoal[slot] == goal.index && hopClearance[slot] == clearanceCells) {
            return NavCell(hopValue[slot])
        }
        val hop = pathfinder.nextHop(from, goal, clearanceCells)
        hopFrom[slot] = from.index
        hopGoal[slot] = goal.index
        hopClearance[slot] = clearanceCells
        hopValue[slot] = hop.index
        return hop
    }

    /**
     * The nearest cell to [cell] a unit needing [clearanceCells] of room can stand on, or
     * [NavCell.NONE].
     *
     * [cell] itself when it already fits, which is the usual answer and costs one array read. Two
     * things need the search: an order dropped on a building - a player clicking a factory should
     * send the unit *to* the factory rather than being told no - and a unit that a new building has
     * been put on top of, which walks out to the nearest open ground.
     *
     * Rings are searched outward, and the nearest cell **in the first ring that has one** is
     * taken, by squared distance in cells with the lower cell index breaking a tie - so the answer
     * is a function of the grid and the cell alone. It is deliberately not the nearest cell to the
     * *unit*: a hundred units sent to the same building would then resolve to a hundred different
     * cells and each search for itself, where the whole point of a group order is that they share
     * one destination and one field.
     *
     * The search stops at [MAX_RESOLVE_RINGS]: past that the order is somewhere else entirely, and
     * an unbounded search would scan the whole map once per unit per tick to answer a question
     * whose answer is "no".
     */
    public fun nearestOpen(cell: NavCell, clearanceCells: Int): NavCell {
        if (!cell.isValid) return NavCell.NONE
        if (grid.fits(cell, clearanceCells)) return cell
        val column = grid.cellX(cell)
        val row = grid.cellY(cell)
        for (ring in 1..MAX_RESOLVE_RINGS) {
            var best = NavCell.NONE
            var bestDistance = Int.MAX_VALUE
            for (candidateRow in row - ring..row + ring) {
                val rowStep = if (candidateRow > row) candidateRow - row else row - candidateRow
                for (candidateColumn in column - ring..column + ring) {
                    val columnStep = if (candidateColumn > column) candidateColumn - column else column - candidateColumn
                    if (maxOf(rowStep, columnStep) != ring) continue
                    val candidate = grid.cellOf(candidateColumn, candidateRow)
                    if (!grid.fits(candidate, clearanceCells)) continue
                    val distance = rowStep * rowStep + columnStep * columnStep
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = candidate
                    }
                }
            }
            if (best.isValid) return best
        }
        return NavCell.NONE
    }

    /**
     * The route from the world point ([fromX], [fromY]) to ([toX], [toY]) for a unit of [radius],
     * written into [out].
     *
     * What `nav.path` answers with, and the same A* the steering uses - a tool that reported a
     * route the units do not take would be worse than no tool. Returns why it failed rather than
     * only that it did, because "the goal is off the map" and "the goal is walled in" want
     * different things from whoever asked.
     */
    public fun path(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        radius: Float,
        out: NavPath,
    ): NavRouteOutcome {
        out.clear()
        val clearanceCells = grid.clearanceCellsFor(radius)
        val from = grid.cellAt(fromX, fromY)
        if (!from.isValid) return NavRouteOutcome.StartOffGrid
        val to = grid.cellAt(toX, toY)
        if (!to.isValid) return NavRouteOutcome.GoalOffGrid
        val start = nearestOpen(from, clearanceCells)
        if (!start.isValid) return NavRouteOutcome.StartBlocked
        val goal = nearestOpen(to, clearanceCells)
        if (!goal.isValid) return NavRouteOutcome.GoalBlocked
        return if (pathfinder.findPath(start, goal, clearanceCells, out)) {
            NavRouteOutcome.Found
        } else {
            NavRouteOutcome.NoRoute
        }
    }

    override fun toString(): String = "Navigation($grid, $fieldSweeps field sweeps)"

    public companion object {

        /**
         * How many flow fields are kept at once.
         *
         * One per live group order. Eight because that is more separate destinations than a player
         * has units selected at once in practice, and eight `IntArray`s of the map is a bounded
         * cost a reviewer can multiply out: at 128x128 cells it is 512KB.
         */
        public const val FIELD_CACHE_SIZE: Int = 8

        /**
         * How far [nearestOpen] looks for standable ground, in cells.
         *
         * Sixteen: at the half-metre cells an RTS uses, eight metres, which covers any single
         * building's footprint plus its neighbours. Past that an order is not "on that building",
         * it is somewhere the unit has no business walking to.
         */
        public const val MAX_RESOLVE_RINGS: Int = 16

        /**
         * Slots in the A* hop cache. A power of two, so the slot is a mask rather than a modulo.
         *
         * A thousand is more distinct `(cell, goal, size)` questions than a tick asks: one unit
         * asks one, and a group asks none at all because a group reads a flow field.
         */
        private const val HOP_CACHE_SIZE: Int = 1024

        /** An odd multiplier, so the three parts of a key do not collapse into one another. */
        private const val HASH_ODD: Int = 31

        /** No cell has this index, so a slot holding it has never been written. */
        private const val EMPTY_SLOT: Int = -1
    }
}

/**
 * Why [Navigation.path] answered the way it did.
 *
 * A sealed set of reasons rather than a nullable path, because every one of them is a different
 * thing for the caller to say: `nav.path` renders them, and a unit turns [NoRoute] and the two
 * blocked cases into `NavState.Unreachable`.
 */
public enum class NavRouteOutcome {

    /** There is a route, and it is in the path buffer. */
    Found,

    /** The start is not on the nav grid at all. */
    StartOffGrid,

    /** The goal is not on the nav grid at all. */
    GoalOffGrid,

    /** The start is under a building, with no open ground within reach of it. */
    StartBlocked,

    /** The goal is under a building, with no open ground within reach of it. */
    GoalBlocked,

    /** Both ends are standable and there is no way from one to the other. */
    NoRoute,
}
