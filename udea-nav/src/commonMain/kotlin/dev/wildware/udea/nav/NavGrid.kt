package dev.wildware.udea.nav

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The ground plane as a grid of square cells: what is blocked, and how much room each open cell
 * has.
 *
 * ## Immutable, and rebuilt rather than edited
 *
 * A grid is built once, by [NavGridBuilder], and never changes afterwards. A building placed or
 * destroyed produces a **new** grid, which [Navigation.rebuild] swaps in and which drops every
 * cached route with it. Two reasons, and both are about determinism rather than tidiness:
 *
 * - a path is a pure function of the grid it was found on, so a cached route can only be stale if
 *   the grid it came from is gone - there is no "the grid changed under me" case to reason about;
 * - a mutable grid would need a "has it changed since?" flag, and a flag that says *changed* is
 *   derived state that a rewind restores to a value from a future that no longer exists.
 *   [NavGridSystem] compares the obstacles against the footprints this grid was **built from**
 *   instead, which is a comparison of two states rather than a memory of an event.
 *
 * ## Clearance, and what a unit needs
 *
 * [clearance] is the Chebyshev distance in cells from a cell to the nearest blocked cell, and the
 * grid's own edge counts as blocked - a unit may not walk off the map. A blocked cell is `0`.
 *
 * A unit of radius `r` needs [clearanceCellsFor] cells of it, which is `ceil(r / cellSize)` and at
 * least one. **What that guarantees, exactly:** a cell with clearance `k` has every cell within
 * `k - 1` of it open, so a unit standing anywhere in it has at least `(k - 1) * cellSize` of open
 * ground in every direction. Its centre therefore never enters a blocked cell, and its *body* may
 * overlap a footprint by at most `r - (k - 1) * cellSize` - which for the common case of a unit
 * smaller than a cell is up to its own radius, at a wall it is walking along.
 *
 * That is the usual grid-pathfinding bargain, and it is stated rather than hidden: a route is
 * decided at cell resolution, so a unit hugs a corner. A game that needs the disc itself never to
 * touch a footprint asks for `clearanceCellsFor(radius + cellSize)`, and pays for it in gaps its
 * units then refuse to use.
 *
 * ## Z is ignored, and so is rotation
 *
 * This is the ground plane of `Transform3D`: `x` and `y`, with `z` and every rotation left to the
 * game (`AGENTS.md`, "Z is up"). A footprint is therefore an axis-aligned rectangle, which is what
 * an RTS building is; a rotated building blocks its axis-aligned bounds, and that is an
 * over-approximation rather than a gap.
 */
public class NavGrid internal constructor(
    /** Where this grid sits and how finely it is cut. */
    public val layout: NavGridLayout,
    /** `1` where blocked, `0` where open. One byte per cell; indexed by [NavCell.index]. */
    private val blocked: ByteArray,
    /** Chebyshev cells to the nearest blocked cell or to the grid's edge. `0` where blocked. */
    private val clearance: IntArray,
    /** The footprints this grid was built from, as `minX, minY, maxX, maxY` cell bounds. */
    internal val footprints: IntArray,
) {

    /** World x of the grid's lower-left corner. */
    public val originX: Float get() = layout.originX

    /** World y of the grid's lower-left corner. */
    public val originY: Float get() = layout.originY

    /** The side of one cell, in metres. */
    public val cellSize: Float get() = layout.cellSize

    /** Cells across. */
    public val width: Int get() = layout.width

    /** Cells up. */
    public val height: Int get() = layout.height

    /** How many cells this grid has. */
    public val cellCount: Int get() = width * height

    /** The cell containing the world point ([x], [y]), or [NavCell.NONE] if it is off the grid. */
    public fun cellAt(x: Float, y: Float): NavCell {
        val column = floor((x - originX) / cellSize)
        val row = floor((y - originY) / cellSize)
        // Compared as floats before narrowing: `toInt()` on a value outside Int's range saturates
        // rather than wrapping, so a point a million metres away would land back on the grid.
        if (column < 0f || row < 0f || column >= width.toFloat() || row >= height.toFloat()) return NavCell.NONE
        return NavCell(row.toInt() * width + column.toInt())
    }

    /** The cell at column [column], row [row], or [NavCell.NONE] if that is off the grid. */
    public fun cellOf(column: Int, row: Int): NavCell =
        if (column < 0 || row < 0 || column >= width || row >= height) {
            NavCell.NONE
        } else {
            NavCell(row * width + column)
        }

    /** [cell]'s column. */
    public fun cellX(cell: NavCell): Int = cell.index % width

    /** [cell]'s row. */
    public fun cellY(cell: NavCell): Int = cell.index / width

    /** World x of [cell]'s centre. */
    public fun centreX(cell: NavCell): Float = originX + (cellX(cell).toFloat() + 0.5f) * cellSize

    /** World y of [cell]'s centre. */
    public fun centreY(cell: NavCell): Float = originY + (cellY(cell).toFloat() + 0.5f) * cellSize

    /** Whether [cell] is covered by a footprint. An invalid cell is blocked. */
    public fun isBlocked(cell: NavCell): Boolean = !cell.isValid || blocked[cell.index].toInt() != 0

    /** Chebyshev cells from [cell] to the nearest blocked cell or edge; `0` where blocked. */
    public fun clearance(cell: NavCell): Int = if (cell.isValid) clearance[cell.index] else 0

    /** Whether a unit needing [clearanceCells] of room may stand on [cell]. */
    public fun fits(cell: NavCell, clearanceCells: Int): Boolean =
        cell.isValid && clearance[cell.index] >= clearanceCells

    /** How many cells of [clearance] a unit of radius [radius] needs. At least one. */
    public fun clearanceCellsFor(radius: Float): Int =
        max(1, ceil(radius / cellSize).toInt())

    override fun toString(): String =
        "NavGrid(${width}x$height @ ${cellSize}m from ($originX, $originY), ${footprints.size / 4} footprints)"
}

/**
 * Builds a [NavGrid]: start from open ground, [block] each footprint, [build].
 *
 * The footprints are kept on the grid as cell bounds, which is what [NavGridSystem] compares the
 * world's obstacles against to decide whether the grid it holds is still the right one.
 */
public class NavGridBuilder(
    /** Where the grid sits and how finely it is cut. */
    public val layout: NavGridLayout,
) {

    private val width = layout.width

    private val height = layout.height

    private val blocked = ByteArray(width * height)

    private val bounds = ArrayList<Int>()

    /** Where [block] turns one rectangle in metres into the four cell bounds it covers. */
    private val footprint = IntArray(4)

    /**
     * Blocks every cell the axis-aligned footprint covers.
     *
     * The footprint covers `[centreX - halfWidth, centreX + halfWidth]` on x and the same on y.
     * A cell is blocked when that rectangle covers any of its **interior**: an edge landing
     * exactly on a cell boundary does not block the cell beyond it, which is what makes a
     * building whose side is a whole number of cells block exactly the cells it stands on. A
     * footprint too small to cover any interior still blocks the cell holding its centre, so a
     * zero-sized one is a blocked cell rather than nothing at all.
     */
    public fun block(centreX: Float, centreY: Float, halfWidth: Float, halfDepth: Float): NavGridBuilder {
        layout.footprintInto(footprint, 0, centreX, centreY, halfWidth, halfDepth)
        return blockCells(footprint[0], footprint[1], footprint[2], footprint[3])
    }

    /** Blocks the cells of a footprint already expressed in cell bounds, as a grid records them. */
    internal fun blockCells(minColumn: Int, minRow: Int, maxColumn: Int, maxRow: Int): NavGridBuilder {
        bounds += minColumn
        bounds += minRow
        bounds += maxColumn
        bounds += maxRow
        for (row in max(0, minRow)..min(height - 1, maxRow)) {
            for (column in max(0, minColumn)..min(width - 1, maxColumn)) {
                blocked[row * width + column] = 1
            }
        }
        return this
    }

    /** The finished grid, with its clearance field computed. */
    public fun build(): NavGrid = NavGrid(
        layout = layout,
        blocked = blocked,
        clearance = clearanceField(),
        footprints = bounds.toIntArray(),
    )

    /**
     * Chebyshev distance from every cell to the nearest blocked cell, and to the grid's edge.
     *
     * A multi-source breadth-first search over the eight-neighbourhood, which *is* Chebyshev
     * distance, seeded with every blocked cell at zero. The edge is folded in afterwards rather
     * than seeded as a ring of virtual cells: the distance to the outside is `min(x + 1, y + 1,
     * width - x, height - y)` in closed form, so a grid with no footprints at all costs one pass
     * of arithmetic instead of a search from its whole border.
     *
     * The result is a distance, so it does not depend on the order cells come off the queue; the
     * queue is a plain `IntArray` ring for allocation rather than for order.
     */
    private fun clearanceField(): IntArray {
        val cells = width * height
        val distance = IntArray(cells) { UNVISITED }
        val queue = IntArray(cells)
        var head = 0
        var tail = 0
        for (index in 0 until cells) {
            if (blocked[index].toInt() != 0) {
                distance[index] = 0
                queue[tail++] = index
            }
        }
        while (head < tail) {
            val index = queue[head++]
            val next = distance[index] + 1
            val column = index % width
            val row = index / width
            for (step in 0 until NavSteps.COUNT) {
                val neighbourColumn = column + NavSteps.DX[step]
                val neighbourRow = row + NavSteps.DY[step]
                if (neighbourColumn < 0 || neighbourRow < 0 || neighbourColumn >= width || neighbourRow >= height) {
                    continue
                }
                val neighbour = neighbourRow * width + neighbourColumn
                if (distance[neighbour] != UNVISITED) continue
                distance[neighbour] = next
                queue[tail++] = neighbour
            }
        }
        for (index in 0 until cells) {
            val column = index % width
            val row = index / width
            val toEdge = min(min(column + 1, row + 1), min(width - column, height - row))
            val toBlocked = if (distance[index] == UNVISITED) toEdge else distance[index]
            distance[index] = min(toBlocked, toEdge)
        }
        return distance
    }

    private companion object {
        const val UNVISITED: Int = Int.MAX_VALUE
    }
}
