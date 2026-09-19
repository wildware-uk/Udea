package dev.wildware.udea.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.layout.PlacedHandler

/**
 * Where the world view goes (issue #234, reopened): the rectangle the docked panels leave free, so
 * the Scene and Game tabs sit between the panels rather than under them.
 *
 * ComposeGL's docking keeps that rectangle to itself - the dock layout is internal to
 * `composegl-debug` - so it is read back from where the panels were really placed. Each docked
 * panel's frame reports its rectangle ([pane]) and the host reports its own ([host]); [free] is what
 * is left between them.
 *
 * The docked panels always leave one rectangle for the game: a dock layout is a tree of splits with
 * exactly one empty leaf in it (`composegl-debug`'s `DockEmpty`). So the free area is found as the
 * largest rectangle bounded by panel edges that no panel covers, less the divider ComposeGL draws
 * between it and each panel beside it ([freeArea]).
 *
 * A panel the player has floated is not docked and leaves no gap: it floats over the view like any
 * floating window, which is what floating it asks for.
 */
internal class ViewArea {

    private var hostBounds by mutableStateOf(Rect.Zero)

    private val panes = mutableStateMapOf<String, Rect>()

    private val handlers = HashMap<String, PlacedHandler>()

    /** Told where the panels' host is: the area the panels are docked in. */
    val host: PlacedHandler = PlacedHandler { node -> hostBounds = node.layoutBoundsInRoot }

    /** Told where panel [id]'s frame is. [PlacedHandler]s compare by identity, so each is made once. */
    fun pane(id: String): PlacedHandler = handlers.getOrPut(id) { PlacedHandler { node -> panes[id] = node.layoutBoundsInRoot } }

    /**
     * The free rectangle in the host's own coordinates, given which panels are docked. Snapshot reads,
     * so a composition that calls it is composed again when a panel moves.
     */
    fun free(docked: (String) -> Boolean): Rect {
        val origin = hostBounds
        val local = Rect(0f, 0f, origin.width, origin.height)
        val occupied = panes.entries
            .filter { (id, bounds) -> docked(id) && !bounds.isEmpty }
            .map { (_, bounds) -> Rect(bounds.left - origin.left, bounds.top - origin.top, bounds.right - origin.left, bounds.bottom - origin.top) }
        return freeArea(local, occupied, DIVIDER)
    }

    override fun toString(): String = "ViewArea(host=$hostBounds, panes=${panes.toMap()})"

    companion object {

        /**
         * How thick ComposeGL's divider between two docked panes is, in design units: its
         * `DockDividerThickness`, which `composegl-debug` keeps internal. `EditorLayoutTest` reads the
         * dividers ComposeGL really drew and fails if the view overlaps one, so a toolkit that changes
         * this is caught there.
         */
        const val DIVIDER: Float = 6f
    }
}

/**
 * The largest rectangle inside [area] that none of [panels] covers, bounded by their edges, less
 * [divider] off every side of it that meets a panel rather than the edge of [area].
 *
 * Every candidate is a rectangle between two of the vertical edges and two of the horizontal edges
 * in play - [area]'s and the panels'. A divider strip between two panels is a candidate too, but it
 * meets a panel on both sides and so loses its whole width to the insets, leaving no area.
 *
 * With no panels this is [area] itself. With panels covering all of it, it is [Rect.Zero].
 */
internal fun freeArea(area: Rect, panels: List<Rect>, divider: Float): Rect {
    if (area.isEmpty) return Rect.Zero
    val inside = panels.map { it.intersect(area) }.filterNot { it.isEmpty }
    if (inside.isEmpty()) return area
    val xs = (listOf(area.left, area.right) + inside.flatMap { listOf(it.left, it.right) }).distinct().sorted()
    val ys = (listOf(area.top, area.bottom) + inside.flatMap { listOf(it.top, it.bottom) }).distinct().sorted()

    var best = Rect.Zero
    var bestArea = 0f
    for (l in xs.indices) for (r in l + 1 until xs.size) for (t in ys.indices) for (b in t + 1 until ys.size) {
        val candidate = Rect(xs[l], ys[t], xs[r], ys[b])
        if (inside.any { it.overlaps(candidate) }) continue
        val free = Rect(
            if (candidate.left > area.left) candidate.left + divider else candidate.left,
            if (candidate.top > area.top) candidate.top + divider else candidate.top,
            if (candidate.right < area.right) candidate.right - divider else candidate.right,
            if (candidate.bottom < area.bottom) candidate.bottom - divider else candidate.bottom,
        )
        if (free.isEmpty) continue
        val size = free.width * free.height
        if (size > bestArea) {
            best = free
            bestArea = size
        }
    }
    return best
}
