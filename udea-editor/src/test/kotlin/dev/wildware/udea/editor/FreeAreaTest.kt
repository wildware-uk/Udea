package dev.wildware.udea.editor

import dev.wildware.composegl.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [freeArea]: the rectangle the docked panels leave, in the shapes a dock layout makes.
 *
 * Every layout here is laid out as ComposeGL lays one out: panes cut from the area by splits, with a
 * [DIVIDER]-wide gap at each cut. `EditorLayoutTest` holds the same rule against the panels ComposeGL
 * really placed.
 */
class FreeAreaTest {

    private val area = Rect(0f, 0f, 1000f, 600f)

    @Test
    fun `with nothing docked the whole area is free`() {
        assertEquals(area, freeArea(area, emptyList(), DIVIDER))
    }

    @Test
    fun `panels down both sides leave the middle, less the divider beside each`() {
        val left = Rect(0f, 0f, 200f, 600f)
        val right = Rect(800f, 0f, 1000f, 600f)
        assertEquals(Rect(206f, 0f, 794f, 600f), freeArea(area, listOf(left, right), DIVIDER))
    }

    @Test
    fun `two panes side by side on the right leave no free strip between them`() {
        // Create | free | History | Asset, as the editor docks them.
        val create = Rect(0f, 0f, 200f, 600f)
        val history = Rect(700f, 0f, 794f, 600f)
        val asset = Rect(800f, 0f, 1000f, 600f)
        assertEquals(Rect(206f, 0f, 694f, 600f), freeArea(area, listOf(create, history, asset), DIVIDER))
    }

    @Test
    fun `a panel along the bottom under a panel down the left leaves the corner above it`() {
        // Left docked first, then Bottom across everything: the bottom pane spans the whole width.
        val bottom = Rect(0f, 406f, 1000f, 600f)
        val left = Rect(0f, 0f, 250f, 400f)
        assertEquals(Rect(256f, 0f, 1000f, 400f), freeArea(area, listOf(left, bottom), DIVIDER))
    }

    @Test
    fun `a long divider strip never wins over a small free area`() {
        // Panels squeeze the free area into a 58 x 60 corner, 3480 square units. The full-height
        // divider strip beside the left panel is 6 x 600, 3600: bigger, and not free.
        val left = Rect(0f, 0f, 470f, 600f)
        val right = Rect(540f, 0f, 1000f, 600f)
        val top = Rect(476f, 0f, 534f, 534f)
        assertEquals(Rect(476f, 540f, 534f, 600f), freeArea(area, listOf(left, right, top), DIVIDER))
    }

    @Test
    fun `panels covering everything leave nothing`() {
        val left = Rect(0f, 0f, 497f, 600f)
        val right = Rect(503f, 0f, 1000f, 600f)
        assertEquals(Rect.Zero, freeArea(area, listOf(left, right), DIVIDER))
    }

    private companion object {
        /** ComposeGL's divider thickness, as the editor uses it. */
        const val DIVIDER = ViewArea.DIVIDER
    }
}
