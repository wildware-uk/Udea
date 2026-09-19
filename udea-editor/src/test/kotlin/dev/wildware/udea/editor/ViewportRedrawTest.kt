package dev.wildware.udea.editor

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * When a paused editor draws its viewport again: when the world may have changed, and not otherwise
 * (issue #194, "Paused world: `invalidate()` only when something changed").
 */
class ViewportRedrawTest {

    @Test
    fun `it draws while the view warms up, then only when the tick or a completed command moves`() {
        val redraw = ViewportRedraw()
        val frames = listOf(
            Tick(5) to 0L,
            Tick(5) to 0L,
            Tick(5) to 0L,
            Tick(5) to 0L,
            Tick(5) to 1L,
            Tick(5) to 1L,
            Tick(6) to 1L,
            Tick(6) to 1L,
        )
        val due = frames.map { (tick, completed) -> redraw.due(tick, completed) }
        assertEquals(listOf(true, true, false, false, true, false, true, false), due)
    }

    @Test
    fun `a running world is due every frame`() {
        val redraw = ViewportRedraw()
        val due = (0L until 10L).map { redraw.due(Tick(it), 0L) }
        assertEquals(List(10) { true }, due)
    }
}
