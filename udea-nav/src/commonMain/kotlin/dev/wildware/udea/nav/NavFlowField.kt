package dev.wildware.udea.nav

/**
 * The cost of walking to one goal, from **every** cell of the grid: what a group move reads.
 *
 * ## Why a group gets one of these instead of a path each
 *
 * A hundred units ordered to the same point ask the same question a hundred times, and A* answers
 * it from a different start each time. One Dijkstra sweep outward from the goal answers it for the
 * whole map at once, so the order costs one search rather than a hundred, and every unit that
 * enters a new cell afterwards reads [nextHop] out of an array.
 *
 * It is also what makes a *crowd* deterministic cheaply. A unit's next step is a function of the
 * cell it stands in and of nothing it remembers, so a client that rewinds and re-simulates a tick
 * asks the same question of the same field and steps the same way - where a unit following a
 * stored path would need that path in the snapshot, and would need every peer to have chosen the
 * same one of several equally short routes.
 *
 * ## What it costs
 *
 * One `IntArray` of the grid's size per live field, and one sweep to fill it. [Navigation] caches
 * them per goal cell and clearance and drops the lot when the grid is rebuilt, so a hundred units
 * ordered to one point build one field, and ten separate orders build ten.
 *
 * The sweep uses the same step costs, the same neighbour order and the same corner rule as
 * [NavPathfinder], so the two planners never disagree about what a route costs - which
 * `NavFlowFieldTest` asserts rather than assumes.
 */
public class NavFlowField(
    /** The grid this field was swept over. */
    public val grid: NavGrid,
    /** The cell every route in it leads to. */
    public val goal: NavCell,
    /** The room a unit reading this field needs; cells with less are not in it. */
    public val clearanceCells: Int,
) {

    /** Cost to [goal] from each cell, [UNREACHABLE] where there is no route. */
    private val cost = IntArray(grid.cellCount) { UNREACHABLE }

    init {
        if (grid.fits(goal, clearanceCells)) sweep()
    }

    /** The cost of walking from [cell] to [goal], or [UNREACHABLE]. */
    public fun cost(cell: NavCell): Int = if (cell.isValid) cost[cell.index] else UNREACHABLE

    /** Whether a unit standing on [cell] can reach [goal] at all. */
    public fun reaches(cell: NavCell): Boolean = cost(cell) != UNREACHABLE

    /**
     * The cell to walk to next from [cell], or [NavCell.NONE] at the goal and from anywhere that
     * cannot reach it.
     *
     * The neighbour that minimises `cost(neighbour) + step`, which is the step the sweep itself
     * took, with the lowest step index in [NavSteps] breaking a tie. Read rather than remembered:
     * the same cell always gives the same answer.
     */
    public fun nextHop(cell: NavCell): NavCell {
        if (cell == goal || !reaches(cell)) return NavCell.NONE
        val column = grid.cellX(cell)
        val row = grid.cellY(cell)
        var best = NavCell.NONE
        var bestTotal = UNREACHABLE
        for (step in 0 until NavSteps.COUNT) {
            val neighbour = neighbourOf(column, row, step) ?: continue
            val neighbourCost = cost[neighbour.index]
            if (neighbourCost == UNREACHABLE) continue
            val total = neighbourCost + NavSteps.cost(step)
            if (total < bestTotal) {
                bestTotal = total
                best = neighbour
            }
        }
        return best
    }

    /**
     * Dijkstra outward from the goal, over the same edges [NavPathfinder] searches.
     *
     * A binary min-heap ordered by `(cost, cell index)`: the index term makes the order total, so
     * two cells of equal cost always come off in the same order. It does not change what the
     * costs are - Dijkstra's answer is unique - but it is what keeps the sweep's own work
     * identical run to run, which is what a replay compares.
     */
    private fun sweep() {
        val open = NavCellHeap(grid.cellCount)
        // Each cell is settled once - its cost is final the first time it comes off the heap -
        // and a settled cell is never pushed again, so the stale entries the heap holds are the
        // improvements a cell went through before it settled.
        val settled = BooleanArray(grid.cellCount)
        cost[goal.index] = 0
        open.push(goal.index, 0, 0)
        while (open.size > 0) {
            val current = open.pop()
            if (settled[current]) continue
            settled[current] = true
            val column = current % grid.width
            val row = current / grid.width
            val here = cost[current]
            for (step in 0 until NavSteps.COUNT) {
                val neighbour = neighbourOf(column, row, step) ?: continue
                if (settled[neighbour.index]) continue
                val candidate = here + NavSteps.cost(step)
                if (candidate >= cost[neighbour.index]) continue
                cost[neighbour.index] = candidate
                open.push(neighbour.index, candidate, 0)
            }
        }
    }

    /**
     * The neighbour of ([column], [row]) in direction [step], or `null` where a unit of this
     * field's clearance may not make that step - off the grid, too little room, or a diagonal
     * between two cells it does not fit between.
     */
    private fun neighbourOf(column: Int, row: Int, step: Int): NavCell? {
        val neighbourColumn = column + NavSteps.DX[step]
        val neighbourRow = row + NavSteps.DY[step]
        val neighbour = grid.cellOf(neighbourColumn, neighbourRow)
        if (!grid.fits(neighbour, clearanceCells)) return null
        if (NavSteps.isDiagonal(step) &&
            (
                !grid.fits(grid.cellOf(neighbourColumn, row), clearanceCells) ||
                    !grid.fits(grid.cellOf(column, neighbourRow), clearanceCells)
                )
        ) {
            return null
        }
        return neighbour
    }

    override fun toString(): String = "NavFlowField(goal=$goal, clearance=$clearanceCells)"

    public companion object {
        /** The cost of a cell with no route to the goal. */
        public const val UNREACHABLE: Int = Int.MAX_VALUE
    }
}
