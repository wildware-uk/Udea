package dev.wildware.udea.nav

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Where the nav grid sits on the ground plane and how finely it is cut.
 *
 * The shape of the grid is a property of the **map**, not of what is standing on it: a game states
 * it once, in [NavModule], and every grid the obstacles are stamped onto afterwards has it. So it
 * is a value the module holds, and a grid carries it back out for anything that has to turn a
 * world point into a cell.
 *
 * ## Choosing a cell size
 *
 * A cell should be about the width of the smallest unit that has to find its way through a gap.
 * Smaller cells cost memory and sweep time as the square, and buy a route that hugs a wall more
 * closely; larger cells refuse gaps that a unit would fit through, because clearance is counted in
 * whole cells.
 */
public class NavGridLayout(
    /** World x of the grid's lower-left corner. */
    public val originX: Float,
    /** World y of the grid's lower-left corner. */
    public val originY: Float,
    /** The side of one cell, in metres. */
    public val cellSize: Float,
    /** Cells across. */
    public val width: Int,
    /** Cells up. */
    public val height: Int,
) {

    init {
        require(width > 0 && height > 0) { "a nav grid needs at least one cell, not ${width}x$height" }
        require(cellSize > 0f) { "a nav grid's cellSize must be positive, not $cellSize" }
    }

    /** How many cells a grid on this layout has. */
    public val cellCount: Int get() = width * height

    /**
     * Writes the cell bounds of an axis-aligned footprint into [out] at [offset], as
     * `minColumn, minRow, maxColumn, maxRow`.
     *
     * The one place a rectangle in metres becomes a rectangle in cells, so that the grid builder
     * and [NavGridSystem] - which compares the world's footprints against the grid's - cannot
     * round it differently. The bounds are **not** clamped to the grid: a footprint half off the
     * map has the bounds it has, and stamping clips them. Clamping here would make two different
     * footprints compare equal.
     *
     * A cell is covered when the rectangle covers any of its interior, so an edge landing exactly
     * on a cell boundary does not take the cell beyond it; a footprint too small to cover any
     * interior still takes the cell its centre is in.
     */
    internal fun footprintInto(
        out: IntArray,
        offset: Int,
        centreX: Float,
        centreY: Float,
        halfWidth: Float,
        halfDepth: Float,
    ) {
        val minColumn = floor((centreX - halfWidth - originX) / cellSize).toInt()
        val minRow = floor((centreY - halfDepth - originY) / cellSize).toInt()
        out[offset] = minColumn
        out[offset + 1] = minRow
        out[offset + 2] = max(minColumn, ceil((centreX + halfWidth - originX) / cellSize).toInt() - 1)
        out[offset + 3] = max(minRow, ceil((centreY + halfDepth - originY) / cellSize).toInt() - 1)
    }

    override fun toString(): String =
        "NavGridLayout(${width}x$height @ ${cellSize}m from ($originX, $originY))"
}
