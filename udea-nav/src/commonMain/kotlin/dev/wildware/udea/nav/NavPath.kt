package dev.wildware.udea.nav

/**
 * A route, as the cells it passes through, starting at the cell it was asked to start from.
 *
 * A buffer the caller owns and hands to [NavPathfinder.findPath], which overwrites it. A path is
 * therefore not a value to keep: read it, or copy what you need out of it, before the next
 * search. That is what keeps the search allocation-free once a caller's buffer has grown to the
 * longest route it has asked for, which matters because the steering systems search once per unit
 * that has entered a new cell.
 */
public class NavPath {

    private var cells: IntArray = IntArray(INITIAL_CAPACITY)

    /** How many cells the route has. `0` when the last search failed. */
    public var size: Int = 0
        private set

    /** The route's total cost, in the integer units of [NavSteps]. `0` when [size] is `0`. */
    public var cost: Int = 0
        internal set

    /** The cell at [step], counting from the start. */
    public fun cellAt(step: Int): NavCell {
        require(step in 0 until size) { "step $step is outside a path of $size cells" }
        return NavCell(cells[step])
    }

    /** The last cell, or [NavCell.NONE] for an empty path. */
    public val destination: NavCell get() = if (size == 0) NavCell.NONE else NavCell(cells[size - 1])

    /** Empties the path. */
    internal fun clear() {
        size = 0
        cost = 0
    }

    /** Appends [cell]. Grows the buffer when it has to. */
    internal fun append(cell: Int) {
        if (size == cells.size) cells = cells.copyOf(cells.size * 2)
        cells[size++] = cell
    }

    /** Reverses the cells in place, which is how a walk back along the parents becomes a route. */
    internal fun reverse() {
        var low = 0
        var high = size - 1
        while (low < high) {
            val held = cells[low]
            cells[low] = cells[high]
            cells[high] = held
            low++
            high--
        }
    }

    override fun toString(): String = "NavPath($size cells, cost $cost)"

    private companion object {
        /** Long enough for the routes a unit walks between two searches; it grows if not. */
        const val INITIAL_CAPACITY: Int = 64
    }
}
