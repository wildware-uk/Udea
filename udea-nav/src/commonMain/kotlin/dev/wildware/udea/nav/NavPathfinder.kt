package dev.wildware.udea.nav

/**
 * A* over a [NavGrid], for one unit at a time.
 *
 * ## Deterministic, and how
 *
 * Three decisions, and between them they are the whole of it:
 *
 * - **integer costs.** `10` and `14` ([NavSteps]), added thousands of times and compared for
 *   equality. In floats, two routes of the same length can compare unequal by an ulp and the
 *   winner would depend on the order the additions happened in.
 * - **a total order on the open set.** Cells come off the heap by `f`, then by `h`, then by cell
 *   index. `f` and `h` leave ties - on open ground there are hundreds - and the index breaks
 *   every one of them, so no two cells ever compare equal and the expansion order is a function
 *   of the grid and the endpoints alone.
 * - **a fixed neighbour order.** [NavSteps]'s table, which the clearance field and [NavFlowField]
 *   walk in as well.
 *
 * No hash-ordered collection is involved: every structure here is an `IntArray` indexed by cell.
 *
 * ## Allocation
 *
 * One pathfinder holds one set of arrays, sized from the grid, and searching allocates nothing -
 * the visited marks are generation-stamped rather than cleared, so a search costs what it expands
 * rather than what the grid holds. A pathfinder belongs to the grid it was built for; when
 * [Navigation] swaps a rebuilt grid in it builds a new one, which is also what makes a stale route
 * impossible rather than merely unlikely.
 *
 * ## Not thread-safe, on purpose
 *
 * One pathfinder holds one open set. Two threads sharing one would interleave in it. A grid is
 * immutable and *is* shared; give each thread its own pathfinder over it.
 */
public class NavPathfinder(
    /** The grid this pathfinder searches, and whose size its arrays are cut to. */
    public val grid: NavGrid,
) {

    private val cellCount = grid.cellCount

    /** Cost from the start, valid where [stamp] holds the current [search]. */
    private val gScore = IntArray(cellCount)

    /** Where each cell was reached from, valid on the same terms. */
    private val parent = IntArray(cellCount)

    /** Which search last wrote a cell's [gScore] and [parent]. Avoids clearing the arrays. */
    private val stamp = IntArray(cellCount)

    /** `1` once a cell has been expanded, stamped by [closedStamp]. */
    private val closed = IntArray(cellCount)

    /** The open set, ordered by `(f, h, cell index)`. See [NavCellHeap] for why that is total. */
    private val open = NavCellHeap(cellCount)

    private var search = 0

    private var closedStamp = 0

    /** The scratch [nextHop] fills, so asking for one hop allocates nothing. */
    private val scratch = NavPath()

    /**
     * Finds the cheapest route from [from] to [goal] for a unit needing [clearanceCells] of room,
     * writing it into [out].
     *
     * Returns `false`, leaving [out] empty, when either end does not fit or no route exists. Both
     * are ordinary answers rather than failures: an order onto a building's footprint is a thing a
     * player does, and the caller turns it into `NavState.Unreachable`.
     */
    public fun findPath(from: NavCell, goal: NavCell, clearanceCells: Int, out: NavPath): Boolean {
        out.clear()
        if (!grid.fits(from, clearanceCells) || !grid.fits(goal, clearanceCells)) return false
        if (from == goal) {
            out.append(from.index)
            return true
        }
        val goalColumn = grid.cellX(goal)
        val goalRow = grid.cellY(goal)
        beginSearch()
        open.push(from.index, NavSteps.heuristic(grid.cellX(from), grid.cellY(from), goalColumn, goalRow), 0)
        gScore[from.index] = 0
        parent[from.index] = -1
        stamp[from.index] = search
        while (open.size > 0) {
            val current = open.pop()
            if (current == goal.index) {
                reconstruct(from.index, goal.index, out)
                return true
            }
            if (closed[current] == closedStamp) continue
            closed[current] = closedStamp
            val column = current % grid.width
            val row = current / grid.width
            val cost = gScore[current]
            for (step in 0 until NavSteps.COUNT) {
                val neighbourColumn = column + NavSteps.DX[step]
                val neighbourRow = row + NavSteps.DY[step]
                val neighbour = grid.cellOf(neighbourColumn, neighbourRow)
                if (!grid.fits(neighbour, clearanceCells)) continue
                // No corner cutting: a diagonal step is allowed only where both of the cells it
                // passes between are open to this unit as well. Without it a unit walks through
                // the diagonal join of two buildings, which is a gap of zero width.
                if (NavSteps.isDiagonal(step) &&
                    (
                        !grid.fits(grid.cellOf(neighbourColumn, row), clearanceCells) ||
                            !grid.fits(grid.cellOf(column, neighbourRow), clearanceCells)
                        )
                ) {
                    continue
                }
                val index = neighbour.index
                if (closed[index] == closedStamp) continue
                val tentative = cost + NavSteps.cost(step)
                if (stamp[index] == search && gScore[index] <= tentative) continue
                gScore[index] = tentative
                parent[index] = current
                stamp[index] = search
                val heuristic = NavSteps.heuristic(neighbourColumn, neighbourRow, goalColumn, goalRow)
                open.push(index, tentative + heuristic, heuristic)
            }
        }
        return false
    }

    /**
     * The next cell a unit at [from] should walk to on its way to [goal], or [NavCell.NONE].
     *
     * **A function of the unit's current cell, and of nothing it remembers.** That is what makes a
     * single unit's route survive a rewind: a client that replays the same tick from a restored
     * snapshot asks the same question and gets the same answer, where a unit following a path it
     * had stored would have to have stored it in the snapshot too.
     */
    public fun nextHop(from: NavCell, goal: NavCell, clearanceCells: Int): NavCell {
        if (!findPath(from, goal, clearanceCells, scratch)) return NavCell.NONE
        return if (scratch.size < 2) NavCell.NONE else scratch.cellAt(1)
    }

    private fun beginSearch() {
        search++
        closedStamp++
        open.clear()
    }

    private fun reconstruct(from: Int, goal: Int, out: NavPath) {
        out.cost = gScore[goal]
        var cursor = goal
        while (cursor != -1) {
            out.append(cursor)
            if (cursor == from) break
            cursor = parent[cursor]
        }
        out.reverse()
    }
}
