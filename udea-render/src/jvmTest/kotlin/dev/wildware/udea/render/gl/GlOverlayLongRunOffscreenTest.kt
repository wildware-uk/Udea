package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test

/**
 * Issue #275 in `RenderMode.Offscreen`: a game with an overlay registered through
 * `registry.overlay { }` keeps running, keeps drawing its overlay and keeps answering captures for
 * [OverlayLongRun.SECONDS] seconds of real frames. A class of its own because Kool allows one
 * context per JVM, and `udeaGlTest` gives each class one.
 */
class GlOverlayLongRunOffscreenTest {

    @Test
    fun `an offscreen game with an overlay is still running and drawing it after sixteen seconds`() {
        OverlayLongRun.run(RenderMode.Offscreen, "offscreen")
    }
}
