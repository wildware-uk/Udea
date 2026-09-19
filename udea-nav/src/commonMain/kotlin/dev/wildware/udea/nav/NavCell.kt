package dev.wildware.udea.nav

import kotlin.jvm.JvmInline

/**
 * One square of a [NavGrid], as its index into that grid's arrays.
 *
 * A value class over `Int` rather than a bare `Int`, for the reason the standards give about
 * domain primitives: every array in the pathfinder is indexed by cell and every one of them also
 * holds plain `Int` costs, parents and stamps, so a bare cell index is a value that can be passed
 * where a cost was meant and compile. It costs nothing at runtime - the boxing a value class
 * avoids is exactly what a per-tick path could not afford.
 *
 * The index is **grid-relative**: `index = y * width + x` of the grid that produced it. A cell
 * from one grid means a different square on another, so a grid rebuilt around a new footprint
 * invalidates every cell held across it. Nothing here holds one across a rebuild; the components
 * hold world coordinates, which survive.
 */
@JvmInline
public value class NavCell(public val index: Int) {

    /** Whether this names a square at all. [NONE] does not. */
    public val isValid: Boolean get() = index >= 0

    override fun toString(): String = if (isValid) "NavCell($index)" else "NavCell.NONE"

    public companion object {
        /** No square: outside the grid, or no route from here. */
        public val NONE: NavCell = NavCell(-1)
    }
}
