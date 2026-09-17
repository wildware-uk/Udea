package dev.wildware.udea.net.relevancy

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A uniform bucket grid over the map, holding this tick's vision sources.
 *
 * ## Why a grid at all
 *
 * Spec 7 names the fog solve as the likely dominant server cost, and the naive solve is why:
 * "for every entity, test every vision source" is `entities x sources x teams`, which at MOBA
 * scale (250 bodies, ~30 sources, 2 teams) is 15 000 distance tests a tick and grows as the
 * product of two things that both grow. The grid turns the source side into "only the sources
 * whose cell could reach me", so the cost tracks *local density* rather than the roster.
 *
 * ## Why CSR and not a `Map<Int, MutableList<Int>>`
 *
 * The solve runs inside `Simulation.step()`, where the standards forbid steady-state
 * allocation and forbid iteration-order-dependent collections. A bucket-per-cell map is both:
 * it allocates a list per occupied cell per tick, and `HashMap` iteration order is not a
 * contract, so two servers would visit sources in different orders and could grant vision from
 * different sources on a tie. This is the textbook counting sort instead — count per cell,
 * prefix-sum into [cellStart], scatter into [slotAt] — over `IntArray`s that are reused for the
 * life of the session. After the first [build] at a given size it allocates nothing, and the
 * scatter order is index order, so the answer is the same on every machine.
 *
 * ## Coordinates outside the grid
 *
 * Clamped, never rejected. A body knocked outside the playable area still has to be replicated
 * correctly, and a fog solve that threw — or silently skipped it — would turn a gameplay bug
 * into an invisible unit. Clamping costs a wider edge cell and is the conservative direction:
 * it can only make something visible that was borderline, never hide something that was not.
 */
public class VisionGrid(

    /** World x of the grid's lower-left corner. */
    public val originX: Float,

    /** World y of the grid's lower-left corner. */
    public val originY: Float,

    /** Side of one square cell, in world units. */
    public val cellSize: Float,

    /** Cells across. */
    public val columns: Int,

    /** Cells up. */
    public val rows: Int,
) {

    init {
        require(cellSize > 0f) { "cellSize must be positive, was $cellSize" }
        require(columns > 0) { "columns must be positive, was $columns" }
        require(rows > 0) { "rows must be positive, was $rows" }
    }

    /** How many cells the grid holds. */
    public val cellCount: Int = columns * rows

    private val counts = IntArray(cellCount + 1)
    private val cursor = IntArray(cellCount + 1)
    private var slots = IntArray(INITIAL_CAPACITY)
    private var cells = IntArray(INITIAL_CAPACITY)
    private var ordered = IntArray(INITIAL_CAPACITY)
    private var pending = 0
    private var built = false

    /** Sources currently held. */
    public val size: Int get() = pending

    /** Drops every source, keeping the arrays. */
    public fun clear() {
        counts.fill(0)
        pending = 0
        built = false
    }

    /**
     * Records that source [slot] sits at ([x], [y]).
     *
     * [slot] is the caller's own dense index into whatever parallel arrays it keeps the source's
     * radius and team in — this class deliberately stores neither, so that adding a property to a
     * vision source is not a change to the grid.
     */
    public fun add(slot: Int, x: Float, y: Float) {
        check(!built) { "the grid was already built this tick; call clear() before adding again" }
        if (pending == slots.size) {
            slots = slots.copyOf(pending * 2)
            cells = cells.copyOf(pending * 2)
            ordered = ordered.copyOf(pending * 2)
        }
        val cell = cellOf(x, y)
        slots[pending] = slot
        cells[pending] = cell
        counts[cell + 1]++
        pending++
    }

    /** Arranges the added sources into buckets. Call once, after the last [add]. */
    public fun build() {
        check(!built) { "the grid was already built this tick" }
        for (cell in 1..cellCount) counts[cell] += counts[cell - 1]
        counts.copyInto(cursor)
        for (entry in 0 until pending) {
            val cell = cells[entry]
            ordered[cursor[cell]] = slots[entry]
            cursor[cell]++
        }
        built = true
    }

    /** First entry of [cell] in [slotAt]. */
    public fun cellStart(cell: Int): Int = counts[cell]

    /** One past the last entry of [cell] in [slotAt]. */
    public fun cellEnd(cell: Int): Int = counts[cell + 1]

    /** The source slot at bucket entry [entry]. */
    public fun slotAt(entry: Int): Int = ordered[entry]

    /** Column of the cell containing world x [x], clamped to the grid. */
    public fun columnOf(x: Float): Int =
        clamp(floor((x - originX) / cellSize).toInt(), columns)

    /** Row of the cell containing world y [y], clamped to the grid. */
    public fun rowOf(y: Float): Int =
        clamp(floor((y - originY) / cellSize).toInt(), rows)

    /** The cell index for ([x], [y]), clamped to the grid. */
    public fun cellOf(x: Float, y: Float): Int = rowOf(y) * columns + columnOf(x)

    /** The cell at ([column], [row]). */
    public fun cellAt(column: Int, row: Int): Int = row * columns + column

    /**
     * How many cells out from a point a query of [radius] has to look.
     *
     * `ceil(radius / cellSize)` and not `radius / cellSize`: a point sitting at the far edge of
     * its own cell reaches one cell further than its centre does, and rounding down here is the
     * classic off-by-one that makes a unit invisible only when it stands near a cell boundary —
     * which reads as random flicker rather than as an arithmetic bug.
     */
    public fun cellReach(radius: Float): Int {
        val exact = radius / cellSize
        val whole = floor(exact).toInt()
        return if (exact > whole) whole + 1 else whole
    }

    /** Lowest column a query centred on [x] with [radius] may touch. */
    public fun minColumn(x: Float, radius: Float): Int = max(0, columnOf(x) - cellReach(radius))

    /** Highest column a query centred on [x] with [radius] may touch. */
    public fun maxColumn(x: Float, radius: Float): Int = min(columns - 1, columnOf(x) + cellReach(radius))

    /** Lowest row a query centred on [y] with [radius] may touch. */
    public fun minRow(y: Float, radius: Float): Int = max(0, rowOf(y) - cellReach(radius))

    /** Highest row a query centred on [y] with [radius] may touch. */
    public fun maxRow(y: Float, radius: Float): Int = min(rows - 1, rowOf(y) + cellReach(radius))

    override fun toString(): String =
        "VisionGrid(${columns}x$rows @ $cellSize from ($originX, $originY), $pending source(s))"

    private fun clamp(value: Int, limit: Int): Int = if (value < 0) 0 else if (value >= limit) limit - 1 else value

    private companion object {

        /** Enough for a full MOBA roster of vision sources before the first grow. */
        const val INITIAL_CAPACITY: Int = 64
    }
}
