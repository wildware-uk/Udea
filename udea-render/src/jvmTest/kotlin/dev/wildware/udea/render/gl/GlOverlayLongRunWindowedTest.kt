package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test

/**
 * Issue #275 in `RenderMode.Windowed`: a game with an overlay registered through
 * `registry.overlay { }` keeps its window open, keeps drawing its overlay and keeps answering
 * captures for [OverlayLongRun.SECONDS] seconds of real frames. The owner's measurement was a
 * window that closed itself about eight seconds in. A class of its own because Kool allows one
 * context per JVM, and `udeaGlTest` gives each class one.
 */
class GlOverlayLongRunWindowedTest {

    @Test
    fun `a windowed game with an overlay is still running and drawing it after sixteen seconds`() {
        OverlayLongRun.run(RenderMode.Windowed, "windowed")
    }
}
