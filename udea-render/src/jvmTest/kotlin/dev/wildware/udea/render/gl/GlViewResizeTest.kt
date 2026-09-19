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
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.ui.DesktopFonts
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.ui.UiScreen
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.WorldViewport
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #234, reopened: **an editor view shown in a `SceneView` takes the `SceneView`'s size, and is
 * still shown once it has.** Read from the window, where a person sees it.
 *
 * The view opens at the frame's size, which is smaller than the `SceneView` it is shown in and of
 * another shape; the first draw asks for the view to be the `SceneView`'s shape, the pipeline resizes
 * the view's pass, and every draw after copies the resized picture - to the panel's edges, with no
 * bars, because the picture is now the panel's shape.
 *
 * Smaller, because a pass that grows is where a resize can go half wrong: new, larger attachments
 * with the old viewport draw the world into one corner of them. The world is red with a blue square
 * in its top-right corner, which is at the panel's top-right corner only if the pass draws into the
 * whole of the new picture.
 */
class GlViewResizeTest {

    @Test
    fun `a view resized to its SceneView fills it, and is still shown after the resize`() {
        GlAvailability.require()

        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> RedWorld(resources) })
        val probe = BackbufferProbe()
        registry.overlay({ probe })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-view-resize",
                windowWidth = WIDTH,
                windowHeight = HEIGHT,
                renderWidth = FRAME_WIDTH,
                renderHeight = FRAME_HEIGHT,
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
        val ui = UiLayer(fonts, Size(WIDTH.toFloat(), HEIGHT.toFloat()))
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            val view = backend.openSceneView(EditorCamera())
            val state = SceneViewState()
            // Drawn again every frame, as a running editor's tab is.
            backend.drive { delta ->
                host.frame(delta)
                state.invalidate()
            }
            backend.show(ui)
            ui.show(TallPanel(view, state))

            awaitFrames(probe, probe.frames.get() + SETTLE_FRAMES)
            probe.window()?.let { writeReport("window.png", it) }

            val size = backend.onRenderThread { view.width to view.height }
            assertEquals(PANEL_WIDTH to PANEL_HEIGHT, size, "the view did not take its SceneView's size")
            for ((x, y) in listOf(
                PANEL_X + PANEL_WIDTH / 2 to PANEL_Y + PANEL_HEIGHT / 2,
                PANEL_X + INSET to PANEL_Y + INSET,
                PANEL_X + PANEL_WIDTH - 1 - INSET to PANEL_Y + PANEL_HEIGHT - 1 - INSET,
            )) {
                val pixel = probe.pixelAt(x, y)
                assertEquals(RED, pixel, "the resized view is not shown at ($x, $y): ${"#%06X".format(pixel)}")
            }
            val corner = probe.pixelAt(PANEL_X + PANEL_WIDTH - 1 - INSET, PANEL_Y + INSET)
            assertEquals(BLUE, corner, "the world's top-right corner is not at the panel's: ${"#%06X".format(corner)}")
            assertTrue(state.draws >= 2, "the SceneView drew once, so it never showed the view after its resize")
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
        File(dir, "view-resize-$name").writeBytes(png)
    }

    /** Red over the whole target it is handed, whatever its size, with a blue square in its top-right corner. */
    private class RedWorld(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            val width = target.width.toFloat()
            val height = target.height.toFloat()
            batch.beginPixels()
            batch.fill(0f, 0f, width, height, Rgba.of(1f, 0f, 0f, 1f))
            batch.fill(width - MARK, height - MARK, MARK.toFloat(), MARK.toFloat(), Rgba.of(0f, 0f, 1f, 1f))
            batch.end()
        }
    }

    /** A grey window with one tall `SceneView` in it, a shape nothing like the frame's, showing [view]. */
    private class TallPanel(private val view: WorldViewport, private val state: SceneViewState) : UiScreen {
        @Composable
        override fun content() {
            Box(Modifier.size(WIDTH.toFloat(), HEIGHT.toFloat()).background(Colour.rgb(GREY.toLong()))) {
                SceneView(
                    state,
                    Modifier.offset(PANEL_X.toFloat(), PANEL_Y.toFloat()).size(PANEL_WIDTH.toFloat(), PANEL_HEIGHT.toFloat()),
                ) {
                    clear(Colour.rgb(0x000000))
                    view.drawInto(this)
                }
            }
        }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360

        /** The frame's size, which the view opens at: smaller than the panel both ways. */
        const val FRAME_WIDTH = 160
        const val FRAME_HEIGHT = 120

        const val PANEL_X = 100
        const val PANEL_Y = 40
        const val PANEL_WIDTH = 200
        const val PANEL_HEIGHT = 300

        /** Pixels in from the panel's edge a colour is read at. */
        const val INSET = 3

        /** The side of the world's corner square, in the view's pixels. */
        const val MARK = 24

        const val FONT = "udea-test"
        const val FONT_SIZE = 16

        const val SETTLE_FRAMES = 8
        const val DEADLINE_SECONDS = 20L

        const val RED = 0xFF0000
        const val BLUE = 0x0000FF
        const val GREY = 0x505050
    }
}
