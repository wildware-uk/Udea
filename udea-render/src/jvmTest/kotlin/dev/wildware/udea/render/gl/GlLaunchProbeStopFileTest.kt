package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.LaunchProbe
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * With a stop file named, the probe writes its frame and then keeps the window open until the file
 * appears: how a launch check holds a host's window up until the client that joins it has connected.
 *
 * A class of its own because Kool allows one context per JVM, and `udeaGlTest` forks one per class.
 */
class GlLaunchProbeStopFileTest {

    @Test
    fun `the window stays open after the frame until the stop file appears, then closes`() {
        GlAvailability.require()
        val dir = Files.createTempDirectory("udea-launch-probe")
        val png = dir.resolve("frame.png")
        val stop = dir.resolve("stop.flag")
        val backend = startProbeBackend(RenderRegistry())
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host::frame, LaunchProbe(png = png, frames = 10, stopFile = stop))

            assertTrue(appearsWithin(png, seconds = 60), "no frame was written to $png")
            // Not closed by the frame: a probe with a stop file waits for it. Two seconds is a
            // hundred frames at the backend's rate, so a probe that closed on the frame would
            // long since have done so.
            assertFalse(exitsWithin(backend, seconds = 2), "the window closed before its stop file appeared")
            assertTrue(backend.renderLoopRunning)

            Files.createFile(stop)
            assertTrue(exitsWithin(backend, seconds = 30), "the stop file appeared and the window stayed open")
            assertFalse(backend.renderLoopRunning)
        } finally {
            backend.close()
        }
    }
}
