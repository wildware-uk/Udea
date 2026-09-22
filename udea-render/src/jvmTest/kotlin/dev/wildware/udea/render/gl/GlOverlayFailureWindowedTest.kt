package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test

/**
 * Issue #275 in `RenderMode.Windowed`, the owner's case: a window that closed itself and a process
 * that exited 0 with nothing logged. An overlay that throws must stop the game loudly instead, with
 * its exception on the failed capture, on `awaitExit()` and on stderr. See [OverlayFailure]. A class
 * of its own because Kool allows one context per JVM.
 */
class GlOverlayFailureWindowedTest {

    @Test
    fun `a windowed game whose overlay throws says why it stopped`() {
        OverlayFailure.run(RenderMode.Windowed, "windowed")
    }
}
