package dev.wildware.udea.render.gl

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.ui.DesktopFonts
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.ui.UiScreen
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #224's second acceptance criterion, in pixels: **a ComposeGL screen draws over the Kool
 * scene, and is not in the frame an agent captures.**
 *
 * ## Why the two halves are one test
 *
 * "The capture did not change" is exactly what an interface that never drew would produce, and
 * `GlOverlayIsolationTest` says the same thing about the agent overlay for the same reason. So the
 * window read-back is what turns "absent from the capture" into a fact about where the interface
 * drew rather than about whether it drew at all.
 *
 * ## Where a ComposeGL screen lives, and why a capture cannot see it
 *
 * `KoolSurface` draws every `RenderSystem` into an `OffscreenPass2d` and presents that pass's
 * texture into the window; a capture reads the pass. [UiLayer] is a Kool `Scene` of its own, added
 * to the context after `udea-screen`, so it draws into the window's framebuffer after the presented
 * texture and never into the pass. That is the same structural argument the agent overlay rests on:
 * not an ordering rule, but two different targets.
 *
 * ## The font is not decoration
 *
 * `src/jvmTest/resources/fonts/DejaVuSans.ttf` is registered because an interface cannot be drawn
 * without a family at all, not because this screen wants a label: the shared renderer samples solid
 * colour from the glyph atlas's white block, and `StbFonts` refuses to prepare an atlas nothing has
 * been registered in (`no fonts were registered`). It is read from this module's own test resources
 * rather than from the machine's font directory, so the picture is the same on every box. Test-only;
 * its licence sits beside it.
 *
 * The assertions are on flat colour and not on glyphs. A label is drawn because a screenshot a
 * person has to read is better with one, and because it exercises the glyph path the boxes do not -
 * but a pixel comparison against rasterised text would be a comparison against the driver's
 * anti-aliasing.
 */
class GlUiLayerTest {

    @Test
    fun `a ComposeGL screen draws over the Kool scene and never into a capture`() {
        GlAvailability.require()

        val (without, with) = runBoth()

        // 1. The interface drew. Without this the rest is a statement about a no-op.
        assertEquals(
            PANEL,
            with.windowCentre,
            "the ComposeGL panel did not reach the window: centre pixel was ${hex(with.windowCentre)}",
        )
        assertNotEquals(
            without.windowCentre,
            with.windowCentre,
            "the window looks the same with the screen shown and hidden",
        )
        assertEquals(
            SCENE,
            without.windowCentre,
            "with no screen shown the window should show the presented Kool scene, was " +
                hex(without.windowCentre),
        )

        // 2. ...and none of it reached the capture, which is byte-for-byte the same picture.
        assertContentEquals(
            without.png,
            with.png,
            "the captured PNG changed when the interface was shown: an agent doing " +
                "capture/act/capture would read the menu as a change in the game",
        )
        assertEquals(
            SCENE,
            with.captureCentre,
            "the capture's centre pixel is ${hex(with.captureCentre)}; ComposeGL pixels reached " +
                "the offscreen pass",
        )
    }

    // --- fixture -------------------------------------------------------------------------

    /** One capture, and one read-back of the window the frame was presented to. */
    private class Run(val png: ByteArray, val captureCentre: Int, val windowCentre: Int)

    /** Both halves, from one boot: screen hidden, then screen shown. */
    private fun runBoth(): Pair<Run, Run> {
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> SceneSystem(resources) })
        val probe = BackbufferProbe()
        registry.overlay({ probe })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-ui-layer",
                windowWidth = WINDOW_WIDTH,
                windowHeight = WINDOW_HEIGHT,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )
        val fonts = DesktopFonts()
        fonts.register(
            FONT,
            checkNotNull(javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
                "the test font is missing from udea-render's jvmTest resources"
            }.readBytes(),
            listOf(FONT_SIZE),
        )
        val ui = UiLayer(fonts, Size(WINDOW_WIDTH.toFloat(), WINDOW_HEIGHT.toFloat()))
        try {
            val host = GameHost(
                RenderMode.Offscreen,
                UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()),
                backend,
            )
            backend.drive(host)
            backend.show(ui)
            val slot = backend.pipeline!!.capture!!

            val without = captureOnce(slot, probe, "hidden")

            ui.show(PanelScreen())
            val with = captureOnce(slot, probe, "shown")

            return without to with
        } finally {
            // The backend owns the layer from `show` on, and closes it on the render thread before
            // the pipeline. The fonts are this test's, and go after the canvas that samples them.
            backend.close()
            fonts.close()
        }
    }

    private fun captureOnce(
        slot: dev.wildware.udea.render.capture.FrameCaptureSlot,
        probe: BackbufferProbe,
        name: String,
    ): Run {
        val result = slot.capture(CaptureRequest())

        // The capture is served mid-frame, before that frame's scenes have drawn. Waiting three
        // whole frames afterwards means the window pixel below is one the interface has had a
        // frame to compose, lay out and draw on - or not, depending on what is shown.
        awaitFrames(probe, probe.frames.get() + 3)

        val image = ImageIO.read(ByteArrayInputStream(result.bytes))
            ?: error("the captured bytes are not a decodable image")
        writeReport("capture-$name.png", result.bytes)
        probe.window()?.let { writeReport("window-$name.png", it) }
        return Run(
            png = result.bytes,
            captureCentre = image.getRGB(image.width / 2, image.height / 2) and 0xFFFFFF,
            windowCentre = probe.centre.get(),
        )
    }

    private fun awaitFrames(probe: BackbufferProbe, target: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (probe.frames.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(probe.frames.get() >= target, "the render thread stopped drawing")
    }

    /** Puts a frame where a person can look at it, when the task said where that is. */
    private fun writeReport(name: String, png: ByteArray) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        File(dir, "ui-$name").writeBytes(png)
    }

    private fun hex(rgb: Int): String = "#%06X".format(rgb)

    /** Fills the capturable target, so the two captures agree on content rather than on blank. */
    private class SceneSystem(private val resources: RenderResources) : RenderSystem {

        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.of(0f, 0f, 1f, 1f))
            batch.end()
        }
    }

    /** A panel over the middle of the window, with a border, and nothing that needs a glyph. */
    private class PanelScreen : UiScreen {

        @Composable
        override fun content() {
            Box(Modifier.size(WINDOW_WIDTH.toFloat(), WINDOW_HEIGHT.toFloat())) {
                Box(
                    Modifier
                        .offset(PANEL_X, PANEL_Y)
                        .size(PANEL_WIDTH, PANEL_HEIGHT)
                        .background(Colour.rgb(PANEL.toLong()))
                        .border(Colour.rgb(BORDER.toLong()), width = 3f),
                )
                // Well clear of the centre pixel the assertions read, so a glyph can never decide
                // the result: what is asserted is the panel's flat colour.
                Text(
                    "ComposeGL over Kool",
                    Modifier.offset(PANEL_X + 24f, PANEL_Y + 16f),
                    textStyle = TextStyle(family = FONT, size = FONT_SIZE.toFloat()),
                    colour = Colour.rgb(BORDER.toLong()),
                )
            }
        }
    }

    private companion object {

        const val WINDOW_WIDTH = 640
        const val WINDOW_HEIGHT = 360

        /** Letterboxes at scale 4 into the window, so the centre is inside the drawn area. */
        const val RENDER_WIDTH = 160
        const val RENDER_HEIGHT = 90

        const val PANEL_X = 180f
        const val PANEL_Y = 100f
        const val PANEL_WIDTH = 280f
        const val PANEL_HEIGHT = 160f

        const val FONT = "udea-test"
        const val FONT_SIZE = 24

        const val SCENE = 0x0000FF
        const val PANEL = 0xC62828
        const val BORDER = 0x4CC2FF
    }
}

/**
 * Reads the window's centre pixel once per frame, and the whole window when asked.
 *
 * An `OverlaySystem` for the reason `GlOverlayIsolationTest`'s probe is one: the pipeline runs
 * overlays last and in registration order, so this is a defined point in the frame. What it reads is
 * the default framebuffer - the window - as it stood when this frame's callbacks ran, which is the
 * previous frame fully drawn, interface included. That one-frame lag is why the test waits for three
 * frames rather than for one.
 *
 * `glReadPixels` through LWJGL rather than Kool's texture download: it reads whatever framebuffer is
 * bound on this thread, which at overlay time is the window's.
 */
internal class BackbufferProbe : OverlaySystem {

    val frames: AtomicInteger = AtomicInteger()

    val centre: AtomicInteger = AtomicInteger()

    private val pixel: ByteBuffer = ByteBuffer.allocateDirect(4)

    /**
     * The whole window, allocated once and reused.
     *
     * Once, not per frame, and that is not a tidy-up: a fresh direct buffer of a window's worth of
     * pixels sixty times a second exhausted the direct-memory pool in about two seconds, and the
     * render thread died with the render loop still apparently healthy - which surfaced as a capture
     * that stalled rather than as anything naming memory.
     */
    private var whole: ByteBuffer = ByteBuffer.allocateDirect(4)

    @Volatile
    private var lastWindow: ByteArray? = null

    @Volatile
    private var width: Int = 0

    @Volatile
    private var height: Int = 0

    override fun render(target: ScreenTarget, dtSeconds: Float) {
        pixel.clear()
        GL11.glReadPixels(
            target.width / 2,
            target.height / 2,
            1,
            1,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            pixel,
        )
        centre.set(
            ((pixel.get(0).toInt() and 0xFF) shl 16) or
                ((pixel.get(1).toInt() and 0xFF) shl 8) or
                (pixel.get(2).toInt() and 0xFF),
        )
        val needed = target.width * target.height * 4
        if (whole.capacity() < needed) whole = ByteBuffer.allocateDirect(needed)
        whole.clear()
        GL11.glReadPixels(0, 0, target.width, target.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, whole)
        val bytes = ByteArray(needed)
        whole.get(bytes)
        lastWindow = bytes
        width = target.width
        height = target.height
        frames.incrementAndGet()
    }

    /** The last window read back, as a PNG with GL's bottom-first rows turned the right way up. */
    fun window(): ByteArray? {
        val bytes = lastWindow ?: return null
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = ((height - 1 - y) * width + x) * 4
                image.setRGB(
                    x,
                    y,
                    ((bytes[i].toInt() and 0xFF) shl 16) or
                        ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                        (bytes[i + 2].toInt() and 0xFF),
                )
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
