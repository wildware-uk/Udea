package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.LaunchProbe
import dev.wildware.udea.render.backend.WindowConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A launch check's probe, against a real context: after its frame count it writes the captured
 * frame to disk and closes the window by itself, so the game's own shutdown path runs.
 *
 * The Windows launch job asks every windowed launcher for exactly this, through the environment,
 * and reads the PNG back. This is the half of that claim a Linux box can check.
 */
class GlLaunchProbeTest {

    @Test
    fun `the probe writes the frame after its count and then closes the window by itself`() {
        GlAvailability.require()
        val png = Files.createTempDirectory("udea-launch-probe").resolve("frame.png")
        val drawn = AtomicInteger()
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { CountingRenderSystem(drawn) })
        val backend = startProbeBackend(registry)
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host::frame, LaunchProbe(png = png, frames = FRAMES, stopFile = null))

            assertTrue(exitsWithin(backend, seconds = 60), "the probe never closed the window")
            assertFalse(backend.renderLoopRunning, "the render loop outlived the probe")
            assertTrue(drawn.get() >= FRAMES, "the window closed after ${drawn.get()} frames, before the probe's $FRAMES")

            assertTrue(Files.isRegularFile(png), "no frame was written to $png")
            val image = checkNotNull(ImageIO.read(png.toFile())) { "$png is not an image" }
            assertEquals(PROBE_RENDER_WIDTH, image.width)
            assertEquals(PROBE_RENDER_HEIGHT, image.height)

            // The positive control a software driver is checked against: which GL answered.
            assertTrue(backend.glInfo.renderer.isNotBlank(), "GL_RENDERER was blank")
            assertTrue(backend.glInfo.version.isNotBlank(), "GL_VERSION was blank")
        } finally {
            backend.close()
        }
    }

    private class CountingRenderSystem(private val frames: AtomicInteger) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            frames.incrementAndGet()
        }
    }

    private companion object {
        const val FRAMES = 30
    }
}

internal const val PROBE_RENDER_WIDTH: Int = 128
internal const val PROBE_RENDER_HEIGHT: Int = 64

internal fun startProbeBackend(registry: RenderRegistry): KoolBackend = KoolBackend.start(
    RenderMode.Offscreen,
    WindowConfig(
        title = "udea-test",
        windowWidth = 320,
        windowHeight = 240,
        renderWidth = PROBE_RENDER_WIDTH,
        renderHeight = PROBE_RENDER_HEIGHT,
    ),
    registry,
)

/** Whether the render loop exits by itself inside [seconds]: `awaitExit` has no deadline of its own. */
internal fun exitsWithin(backend: KoolBackend, seconds: Long): Boolean {
    val waiter = thread(isDaemon = true, name = "await-exit") { backend.awaitExit() }
    waiter.join(TimeUnit.SECONDS.toMillis(seconds))
    return !waiter.isAlive
}

/** Polls for [path] to exist, twenty times a second for up to [seconds]. Counts polls, reads no clock. */
internal fun appearsWithin(path: Path, seconds: Long): Boolean {
    var polls = seconds * 20
    while (!Files.exists(path) && polls-- > 0) Thread.sleep(50)
    return Files.exists(path)
}
