package dev.wildware.udea.render.gl

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.BufferUtils
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.OverlayResources
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.Lwjgl3Backend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec 3.7's two-sided overlay assertion, in pixels: **the capture is identical with the overlay
 * on and off, and the window is not**.
 *
 * ## Why both halves
 *
 * The spec asks for both because the first alone passes just as happily when the overlay is
 * broken. "Two captures matched" is exactly what you get from an overlay that never drew, an
 * overlay registered into the wrong phase and skipped, or a `RenderPhase.Overlay` list the
 * pipeline forgot to walk. The window readback is what says the overlay *did* draw, so the
 * matching captures mean it drew somewhere a capture cannot see.
 *
 * ## What existed before
 *
 * A call-order log over fakes. `CaptureOrderingTest` drives a `RecordingSurface` that records
 * `begin`/`endAndPresent` and a `RecordingOverlaySystem` that records a string, and asserts the
 * sequence — which is worth having and is not this. No test drew overlay pixels and compared
 * captures, and the GL suite registered no `OverlaySystem` at all, so a `GlFrameSurface` that
 * forgot `buffer.end()` before the blit would have left every ordering test green while every
 * agent screenshot carried the agent's own narration.
 *
 * ## The scene is deliberately not empty
 *
 * Both captures would match if the offscreen target were black in both runs, and that would be
 * two blank images agreeing. [BlueSceneSystem] fills the frame, so the captures agree on
 * *content*, and the assertion that the capture's centre pixel is blue rather than red is the
 * one that would fail if overlay pixels reached the framebuffer.
 */
class GlOverlayIsolationTest {

    @Test
    fun `an overlay reaches the window and never the capture`() {
        GlAvailability.require()

        val without = runOnce(overlay = false)
        val with = runOnce(overlay = true)

        // 1. The overlay drew. Without this the rest is a statement about a no-op.
        assertEquals(
            RED,
            with.windowCentre,
            "the overlay did not reach the window: centre pixel was ${hex(with.windowCentre)}",
        )
        assertNotEquals(
            without.windowCentre,
            with.windowCentre,
            "the window looks the same with the overlay on and off",
        )
        assertEquals(
            BLUE,
            without.windowCentre,
            "without the overlay the window should show the blitted scene, was " +
                hex(without.windowCentre),
        )

        // 2. ...and none of it reached the capture, which is byte-for-byte the same picture.
        assertContentEquals(
            without.png,
            with.png,
            "the captured PNG changed when the overlay was switched on: an agent doing " +
                "capture/act/capture would read its own narration as a change in the game",
        )
        assertEquals(
            BLUE,
            with.captureCentre,
            "the capture's centre pixel is ${hex(with.captureCentre)}; overlay pixels reached " +
                "the offscreen framebuffer",
        )
    }

    // --- fixture -------------------------------------------------------------------------

    /** One boot, one capture, and one readback of the window the frame was blitted to. */
    private class Run(val png: ByteArray, val captureCentre: Int, val windowCentre: Int)

    private fun runOnce(overlay: Boolean): Run {
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> BlueSceneSystem(resources) })
        if (overlay) {
            registry.overlay({ resources -> RedOverlaySystem(resources) })
        }
        // Always last, and always registered: the probe is how the window is read, and reading
        // it has to happen at the same point in the frame in both runs.
        val probe = BackbufferProbe()
        registry.overlay({ probe })

        val backend = Lwjgl3Backend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-overlay-isolation",
                windowWidth = WINDOW_WIDTH,
                windowHeight = WINDOW_HEIGHT,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(modules = emptyList()), backend)
            backend.drive(host)
            val slot = backend.pipeline!!.capture!!

            val result = slot.capture(CaptureRequest())

            // The capture is served mid-frame, before the overlays of that frame have run. Wait
            // for two whole frames to complete after it, so the pixel read below is one the
            // overlay has definitely drawn on.
            awaitFrames(probe, probe.frames.get() + 2)

            val image = ImageIO.read(ByteArrayInputStream(result.bytes))
                ?: error("the captured bytes are not a decodable image")
            return Run(
                png = result.bytes,
                captureCentre = image.getRGB(image.width / 2, image.height / 2) and 0xFFFFFF,
                windowCentre = probe.centre.get(),
            )
        } finally {
            backend.close()
        }
    }

    private fun awaitFrames(probe: BackbufferProbe, target: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (probe.frames.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(probe.frames.get() >= target, "the render thread stopped drawing")
    }

    private fun hex(rgb: Int): String = "#%06X".format(rgb)

    /** Fills the capturable target with opaque blue, at a position no wall clock decides. */
    private class BlueSceneSystem(private val resources: RenderResources) : RenderSystem {

        private val projection = Matrix4()
        private val pixel = TextureRegion(resources.own(whitePixel()))

        override fun render(target: OffscreenTarget, alpha: Float) {
            projection.setToOrtho2D(0f, 0f, target.width.toFloat(), target.height.toFloat())
            val batch = resources.batch
            batch.projectionMatrix = projection
            batch.color = Color.BLUE
            batch.begin()
            batch.draw(pixel, 0f, 0f, target.width.toFloat(), target.height.toFloat())
            batch.end()
            batch.color = Color.WHITE
        }
    }

    /**
     * Fills the **window** with opaque red, after the capture point.
     *
     * Takes [OverlayResources] and not `RenderResources` — that is the spec 3.7 split, and there
     * is deliberately no expression here that could reach the offscreen target.
     */
    private class RedOverlaySystem(private val resources: OverlayResources) : OverlaySystem {

        private val projection = Matrix4()
        private val pixel = TextureRegion(resources.own(whitePixel()))

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            projection.setToOrtho2D(0f, 0f, target.width.toFloat(), target.height.toFloat())
            val batch = resources.batch
            batch.projectionMatrix = projection
            batch.color = Color.RED
            batch.begin()
            batch.draw(pixel, 0f, 0f, target.width.toFloat(), target.height.toFloat())
            batch.end()
            batch.color = Color.WHITE
        }
    }

    /**
     * Reads the centre pixel of whatever is bound when the overlays have finished — the window.
     *
     * An `OverlaySystem` rather than a hook, because the point in the frame this has to run at
     * is "after the last overlay", and the pipeline already orders overlays by registration
     * index. `glReadPixels` reads the bound framebuffer, and by this point the offscreen one has
     * been unbound and the frame blitted, so this is the human's picture and not the agent's.
     */
    private class BackbufferProbe : OverlaySystem {

        val frames = AtomicInteger()
        val centre = AtomicInteger()

        private val buffer = BufferUtils.newByteBuffer(4)

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            buffer.clear()
            Gdx.gl.glReadPixels(
                target.width / 2,
                target.height / 2,
                1,
                1,
                GL20.GL_RGBA,
                GL20.GL_UNSIGNED_BYTE,
                buffer,
            )
            val r = buffer.get(0).toInt() and 0xFF
            val g = buffer.get(1).toInt() and 0xFF
            val b = buffer.get(2).toInt() and 0xFF
            centre.set((r shl 16) or (g shl 8) or b)
            frames.incrementAndGet()
        }
    }

    private companion object {

        const val WINDOW_WIDTH = 320
        const val WINDOW_HEIGHT = 240

        /**
         * 64x32 into a 320x240 window: the blit is letterboxed at scale 5, so the drawn area is
         * 320x160 and the window's centre pixel lands inside it rather than in the black bars.
         */
        const val RENDER_WIDTH = 64
        const val RENDER_HEIGHT = 32

        const val RED = 0xFF0000
        const val BLUE = 0x0000FF

        /** A one-pixel white texture, tinted at the draw call. No asset pipeline needed. */
        fun whitePixel(): Texture = Texture(
            Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                setColor(Color.WHITE)
                fill()
            },
        )
    }
}
