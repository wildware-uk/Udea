package dev.wildware.udea.render.gl

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.SceneViewState
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
import dev.wildware.udea.render.ui.DesktopFonts
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.ui.UiScreen
import dev.wildware.udea.render.ui.WorldView
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #194, the viewport half: **the world Kool draws appears inside a ComposeGL `SceneView`**, the
 * right way up and letterboxed, while the panels around it stay out of every capture.
 *
 * ## What is drawn, and why it is lopsided
 *
 * The world is two flat bands - red over green - so a picture that arrives upside down reads green
 * over red and fails, and a picture that arrives at all is told apart from the panel's own clear
 * colour and from the grey interface around it. The pass is 160x90 and the panel 320x180, the same
 * shape, so the whole panel is world and none of it is letterbox bar: each of the four sample points
 * below is well inside one band.
 *
 * ## Why the capture is read too
 *
 * `WorldView` reads the capturable pass and draws it into the *window*; nothing may flow the other
 * way. So the capture is checked for the interface's grey: an editor panel reaching a screenshot is
 * the regression `GlUiLayerTest` pins for a screen, restated for a screen that contains the world.
 */
class GlWorldViewTest {

    @Test
    fun `a SceneView shows the capturable world the right way up, and the capture never shows the panels`() {
        GlAvailability.require()

        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> BandsSystem(resources) })
        val probe = BackbufferProbe()
        registry.overlay({ probe })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-world-view",
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
            val viewport = SceneViewState()
            val screen = ViewportScreen(backend.worldView(), viewport)
            // A live view: drawn again every frame, which is what a view of a running world does.
            backend.drive { delta ->
                host.frame(delta)
                viewport.invalidate()
            }
            backend.show(ui)
            ui.show(screen)

            awaitFrames(probe, probe.frames.get() + SETTLE_FRAMES)
            probe.window()?.let { writeReport("window.png", it) }

            val top = probe.pixelAt(PANEL_X + PANEL_WIDTH / 2, PANEL_Y + PANEL_HEIGHT / 4)
            val bottom = probe.pixelAt(PANEL_X + PANEL_WIDTH / 2, PANEL_Y + PANEL_HEIGHT * 3 / 4)
            val outside = probe.pixelAt(PANEL_X / 2, PANEL_Y / 2)
            assertEquals(GREY, outside, "the interface around the panel is ${hex(outside)}")
            assertEquals(RED, top, "the top of the panel should show the world's top band, was ${hex(top)}")
            assertEquals(GREEN, bottom, "the bottom of the panel should show the world's bottom band, was ${hex(bottom)}")
            assertTrue(viewport.draws >= 1, "the SceneView never drew, so this proves nothing about it")

            val capture = backend.pipeline!!.capture!!.capture(CaptureRequest())
            writeReport("capture.png", capture.bytes)
            val image = ImageIO.read(ByteArrayInputStream(capture.bytes))
            val colours = buildSet {
                for (y in 0 until image.height) for (x in 0 until image.width) add(image.getRGB(x, y) and RGB)
            }
            assertEquals(setOf(RED, GREEN), colours, "the capture holds colours the world never drew")
        } finally {
            backend.close()
            fonts.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun awaitFrames(probe: BackbufferProbe, target: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS)
        while (probe.frames.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(probe.frames.get() >= target, "the render thread stopped drawing")
    }

    private fun writeReport(name: String, png: ByteArray) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        File(dir, "world-view-$name").writeBytes(png)
    }

    private fun hex(rgb: Int): String = "#%06X".format(rgb)

    /** Red over green, in the capturable target's pixels, which count up from the bottom. */
    private class BandsSystem(private val resources: RenderResources) : RenderSystem {

        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            val half = target.height / 2f
            batch.beginPixels()
            batch.fill(0f, 0f, target.width.toFloat(), half, Rgba.of(0f, 1f, 0f, 1f))
            batch.fill(0f, half, target.width.toFloat(), half, Rgba.of(1f, 0f, 0f, 1f))
            batch.end()
        }
    }

    /** A grey window with one `SceneView` in it, showing the world. */
    private class ViewportScreen(private val world: WorldView, private val state: SceneViewState) : UiScreen {

        @Composable
        override fun content() {
            Box(Modifier.size(WINDOW_WIDTH.toFloat(), WINDOW_HEIGHT.toFloat()).background(Colour.rgb(GREY.toLong()))) {
                SceneView(
                    state,
                    Modifier.offset(PANEL_X.toFloat(), PANEL_Y.toFloat()).size(PANEL_WIDTH.toFloat(), PANEL_HEIGHT.toFloat()),
                ) {
                    clear(Colour.rgb(0x000000))
                    world.drawInto(this)
                }
            }
        }
    }

    private companion object {
        const val WINDOW_WIDTH = 640
        const val WINDOW_HEIGHT = 360
        const val RENDER_WIDTH = 160
        const val RENDER_HEIGHT = 90

        const val PANEL_X = 160
        const val PANEL_Y = 90
        const val PANEL_WIDTH = 320
        const val PANEL_HEIGHT = 180

        const val FONT = "udea-test"
        const val FONT_SIZE = 16

        /** Enough frames for the pass to draw, the panel to lay out, render and be read back. */
        const val SETTLE_FRAMES = 6
        const val DEADLINE_SECONDS = 20L

        const val RGB = 0xFFFFFF
        const val RED = 0xFF0000
        const val GREEN = 0x00FF00
        const val GREY = 0x505050
    }
}
