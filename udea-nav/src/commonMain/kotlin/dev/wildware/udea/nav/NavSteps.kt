package dev.wildware.udea.nav

/**
 * The eight steps from a cell, in **one** fixed order, with their integer costs.
 *
 * ## Why the order is a contract and not a detail
 *
 * Two routes of equal cost are both optimal, and which one a search returns is decided by the
 * order it considered the neighbours in. That decision has to be the same on the server, on a
 * predicting client and in a replay, so it is made once, here, rather than by whatever order a
 * loop happened to be written in three different files. Every walk over the grid - the clearance
 * field, [NavPathfinder], [NavFlowField] - steps through this table.
 *
 * The order is anticlockwise from east: E, NE, N, NW, W, SW, S, SE. The even indices are the
 * straight steps, which is what [isDiagonal] reads.
 *
 * ## Why the costs are integers
 *
 * A cost of `10` for a straight step and `14` for a diagonal is the usual integer octile
 * approximation of `1` and `sqrt(2)`. Integers rather than floats because a search adds costs
 * thousands of times and compares them for equality: in floats, two routes that *are* the same
 * length can compare unequal by an ulp, and which one wins would then depend on the order the
 * additions happened in - the one thing a replay cannot reproduce for free. `14/10` is within
 * 1.01% of `sqrt(2)`, and the error is a route that looks very slightly different from the
 * shortest one, not a route that differs between two machines.
 */
internal object NavSteps {

    /** Column deltas, indexed by step. */
    val DX: IntArray = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)

    /** Row deltas, indexed by step. */
    val DY: IntArray = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    /** How many steps there are. */
    const val COUNT: Int = 8

    /** The cost of a straight step. */
    const val STRAIGHT: Int = 10

    /** The cost of a diagonal step. */
    const val DIAGONAL: Int = 14

    /** Whether step [step] is a diagonal. */
    fun isDiagonal(step: Int): Boolean = step % 2 == 1

    /** The cost of step [step]. */
    fun cost(step: Int): Int = if (isDiagonal(step)) DIAGONAL else STRAIGHT

    /**
     * The octile heuristic from ([fromColumn], [fromRow]) to ([toColumn], [toRow]).
     *
     * `DIAGONAL * min + STRAIGHT * (max - min)`: the exact cost of the same move across open
     * ground, so it never over-estimates and A* stays optimal, and it is consistent, so no cell
     * is ever reopened.
     */
    fun heuristic(fromColumn: Int, fromRow: Int, toColumn: Int, toRow: Int): Int {
        val dx = if (toColumn > fromColumn) toColumn - fromColumn else fromColumn - toColumn
        val dy = if (toRow > fromRow) toRow - fromRow else fromRow - toRow
        val low = if (dx < dy) dx else dy
        val high = if (dx < dy) dy else dx
        return DIAGONAL * low + STRAIGHT * (high - low)
    }
}
