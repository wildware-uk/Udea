package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.CaptureOutcome
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Issue #275's repro, as a fixture both render modes share: a game with one overlay, run for longer
 * than the eight seconds after which a game with an overlay used to close itself.
 *
 * The overlay is the one the issue describes - tinted quads cut from a 2x2 white [SpriteTexture]
 * the game made itself, drawn through `OverlayResources.batch` - and it goes in through the public
 * `registry.overlay { }` call a game uses, not through anything internal.
 *
 * Every second of real frames it asks three questions, and each is asked of the running game
 * rather than of a log: is the render loop still running, has the overlay drawn since last time,
 * and does a capture of the game still come back as a picture. At the end it reads the window
 * itself back, where the overlay is, and checks the overlay's pixels are on it.
 */
internal object OverlayLongRun {

    /** Longer than the ~8 s the owner measured, with room either side of it. */
    const val SECONDS: Int = 16

    const val WINDOW_WIDTH: Int = 320
    const val WINDOW_HEIGHT: Int = 240
    const val RENDER_WIDTH: Int = 160
    const val RENDER_HEIGHT: Int = 120

    /** The panel's tint, and what the window pixel under it must read. */
    const val PANEL_RGB: Int = 0x33CC66

    /** What the game draws, so the capture has content of its own to agree on. */
    const val SCENE_RGB: Int = 0x2040A0

    fun run(mode: RenderMode, name: String) {
        GlAvailability.require()

        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> SceneFill(resources) })
        val overlay = AtomicReference<TintedQuads?>(null)
        registry.overlay({ resources -> TintedQuads(resources.batch).also { overlay.set(it) } })
        // Last, so it reads the window once every overlay has drawn on it.
        val probe = WindowProbe()
        registry.overlay({ probe })

        val backend = KoolBackend.start(
            mode,
            WindowConfig(
                title = "udea-overlay-long-run-$name",
                windowWidth = WINDOW_WIDTH,
                windowHeight = WINDOW_HEIGHT,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(mode, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            val quads = assertNotNull(overlay.get(), "registry.overlay's factory never ran")

            var lastDrawn = 0
            var lastTick = 0L
            for (second in 1..SECONDS) {
                sleepOneSecond()
                assertTrue(
                    backend.renderLoopRunning,
                    "$mode: the render loop stopped ${second}s in, with an overlay registered",
                )
                val drawn = quads.frames.get()
                assertTrue(
                    drawn > lastDrawn,
                    "$mode: the overlay drew no frame in second $second (still $drawn)",
                )
                lastDrawn = drawn
                val tick = host.totalTicks
                assertTrue(tick > lastTick, "$mode: the simulation did not advance in second $second")
                lastTick = tick
                val outcome = host.screenshot()
                val captured = outcome as? CaptureOutcome.Captured
                    ?: fail("$mode: a capture ${second}s in came back $outcome, not a picture")
                val image = ImageIO.read(ByteArrayInputStream(captured.image))
                    ?: fail("$mode: the capture ${second}s in is not a decodable image")
                assertEquals(
                    SCENE_RGB,
                    image.getRGB(image.width / 2, image.height / 2) and 0xFFFFFF,
                    "$mode: the capture ${second}s in does not show the game's scene",
                )
                if (second == SECONDS) save(image, "issue275-$name-capture.png")
            }

            // Two whole frames after the last check, so the window read is of a frame drawn now.
            awaitFrames(probe, probe.frames.get() + 2)
            val window = assertNotNull(probe.image.get(), "the window was never read back")
            save(window, "issue275-$name-window.png")
            assertEquals(
                PANEL_RGB,
                window.getRGB(TintedQuads.PANEL_X + 4, window.height - 1 - (TintedQuads.PANEL_Y + 4)) and 0xFFFFFF,
                "$mode: the overlay's panel is not on the window after ${SECONDS}s",
            )
            assertTrue(backend.renderLoopRunning, "$mode: the render loop stopped at the end of the run")
        } finally {
            backend.close()
        }
    }

    private fun sleepOneSecond() {
        Thread.sleep(TimeUnit.SECONDS.toMillis(1))
    }

    private fun awaitFrames(probe: WindowProbe, target: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (probe.frames.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(probe.frames.get() >= target, "the render thread stopped drawing")
    }

    private fun save(image: BufferedImage, file: String) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, file))
    }

    /** The game's own picture: the capturable frame filled with one colour. */
    private class SceneFill(private val resources: RenderResources) : RenderSystem {
        private val colour = Rgba.of(0x20 / 255f, 0x40 / 255f, 0xA0 / 255f, 1f)

        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, target.width.toFloat(), target.height.toFloat(), colour)
            batch.end()
        }
    }

    /**
     * The overlay from the issue: a heads-up panel of tinted quads, every one cut from a 2x2 white
     * texture the game made itself, drawn through the overlay's own batch every frame.
     */
    class TintedQuads(private val batch: SpriteBatch2D) : OverlaySystem {

        val frames = AtomicInteger()

        private val white: SpriteRegion by lazy {
            SpriteRegion(SpriteTexture.fromRgba(2, 2, ByteArray(2 * 2 * 4) { -1 }, "issue275-white"), 0, 0, 2, 2)
        }

        private val panel = Rgba.of(0x33 / 255f, 0xCC / 255f, 0x66 / 255f, 1f)
        private val bar = Rgba.of(0.9f, 0.6f, 0.2f, 1f)

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            batch.beginPixels()
            batch.draw(white, PANEL_X.toFloat(), PANEL_Y.toFloat(), PANEL_WIDTH.toFloat(), PANEL_HEIGHT.toFloat(), panel)
            // A bar that moves each frame, so every frame's instance data differs from the last.
            val fill = (frames.get() % 60) / 60f
            batch.draw(white, PANEL_X + 8f, PANEL_Y + PANEL_HEIGHT + 4f, (PANEL_WIDTH - 16) * fill + 1f, 6f, bar)
            batch.end()
            frames.incrementAndGet()
        }

        companion object {
            /** Window pixels, origin at the bottom left. */
            const val PANEL_X: Int = 12
            const val PANEL_Y: Int = 12
            const val PANEL_WIDTH: Int = 120
            const val PANEL_HEIGHT: Int = 30
        }
    }

    /** Reads the whole window once every overlay has drawn - the human's picture, not the agent's. */
    private class WindowProbe : OverlaySystem {
        val frames = AtomicInteger()
        val image = AtomicReference<BufferedImage?>(null)

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            val width = target.width
            val height = target.height
            val buffer: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4)
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
            val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val at = (y * width + x) * 4
                    val r = buffer.get(at).toInt() and 0xFF
                    val g = buffer.get(at + 1).toInt() and 0xFF
                    val b = buffer.get(at + 2).toInt() and 0xFF
                    // GL's rows run bottom-up; the image's run top-down.
                    out.setRGB(x, height - 1 - y, (r shl 16) or (g shl 8) or b)
                }
            }
            image.set(out)
            frames.incrementAndGet()
        }
    }
}
