package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.view.EditorCamera
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #234, reopened: **while an editor view is open, the frame is not also presented to the
 * window.** The editor shows the world in its views and nowhere else, and a frame presented under
 * the editor's window shows through wherever the window is not opaque - the edges of its panels, in
 * the editor as it was.
 *
 * Read from the window itself with [BackbufferProbe], through the whole life of a view: the frame is
 * in the window before a view opens, gone while one is open, and back once it closes - so the middle
 * reading is the view's doing and not a window that never drew. The capture holds the frame
 * throughout: what an agent's `render.screenshot` reads does not depend on the window.
 */
class GlViewPresentTest {

    @Test
    fun `the window shows the frame until an editor view opens, and again once it closes`() {
        GlAvailability.require()

        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> RedFrame(resources) })
        val probe = BackbufferProbe()
        registry.overlay({ probe })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "udea-view-present", windowWidth = WIDTH, windowHeight = HEIGHT, renderWidth = WIDTH, renderHeight = HEIGHT),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            val slot = backend.pipeline!!.capture!!

            val before = settle(probe)
            val view = backend.openSceneView(EditorCamera())
            val open = settle(probe)
            val captured = captureCentre(slot.capture(CaptureRequest()).bytes)
            backend.onRenderThread { view.close() }
            val after = settle(probe)

            assertEquals(RED, before, "with no view open the window should show the frame, was ${hex(before)}")
            assertEquals(BLACK, open, "with an editor view open the frame was still presented to the window: ${hex(open)}")
            assertEquals(RED, captured, "the capture lost the frame while a view was open: ${hex(captured)}")
            assertEquals(RED, after, "the frame did not come back to the window once the view closed: ${hex(after)}")
        } finally {
            backend.close()
        }
    }

    /**
     * The window's centre pixel a few frames from now. The probe reads the previous frame, so a
     * change made now is certainly in the window by the last of them.
     */
    private fun settle(probe: BackbufferProbe): Int {
        val target = probe.frames.get() + SETTLE_FRAMES
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (probe.frames.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
        check(probe.frames.get() >= target) { "the render thread stopped drawing" }
        return probe.centre.get()
    }

    private fun captureCentre(png: ByteArray): Int {
        val image = checkNotNull(ImageIO.read(ByteArrayInputStream(png))) { "the captured bytes are not a decodable image" }
        return image.getRGB(image.width / 2, image.height / 2) and RGB
    }

    private fun hex(rgb: Int) = "#%06X".format(rgb)

    /** Red over the whole frame. */
    private class RedFrame(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.of(1f, 0f, 0f))
            batch.end()
        }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        const val RGB = 0xFFFFFF
        const val RED = 0xFF0000

        /** The window scene's clear colour: what the window shows with nothing presented. */
        const val BLACK = 0x000000

        const val SETTLE_FRAMES = 4
    }
}
