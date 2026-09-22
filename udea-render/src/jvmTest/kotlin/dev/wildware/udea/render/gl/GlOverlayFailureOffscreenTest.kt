package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test

/**
 * Issue #275 in `RenderMode.Offscreen`: an overlay that throws stops the game loudly, with its
 * exception on the failed capture, on `awaitExit()` and on stderr. See [OverlayFailure]. A class of
 * its own because Kool allows one context per JVM.
 */
class GlOverlayFailureOffscreenTest {

    @Test
    fun `an offscreen game whose overlay throws says why it stopped`() {
        OverlayFailure.run(RenderMode.Offscreen, "offscreen")
    }
}
