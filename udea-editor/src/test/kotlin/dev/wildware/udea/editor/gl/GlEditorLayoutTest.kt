package dev.wildware.udea.editor.gl

import de.fabmax.kool.input.PointerInput
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.editor.EditorTab
import dev.wildware.udea.editor.EditorTags
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.editor.editorFonts
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
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.kool.KoolPointer
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.view.EditorCamera
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #234, reopened: **the world view occupies its own rectangle between the docked panels.** On a
 * real Kool backend, read back from the window itself - what a person looking at the editor sees.
 *
 * The world is one colour, magenta, over far more than any camera frames, so every pixel the world
 * reaches is tinted by it; nothing else in the editor's skin is. So:
 *
 * - under every docked panel and every divider there is **no** world-tinted pixel - on the base this
 *   ticket started from, the world shows through the panels' translucent bodies;
 * - in the view there is world, and it runs from the view's left edge to its right, so nothing is
 *   drawn over the view's edges either - an opaque divider over the view hides the world rather than
 *   showing it, which the first check alone would miss;
 * - and nowhere outside the view is there any, a margin allowed at its edge for the headless twin the
 *   rectangles are read from (below).
 *
 * Both tabs, the Scene tab through its editor camera and the Game tab as the game's own frame. Then the
 * input half, in the Game tab where the pointer is the game's: a click just inside the view's edge
 * reaches the game, and a click just outside it, on the panel, does not.
 *
 * Where the panels and the view are is read from the same window laid out with no GL at the same size,
 * by the tags ComposeGL and the editor put on them: the bundled font's line height differs from the
 * headless one's by a few pixels, which is what [MARGIN_DOWN] allows.
 */
class GlEditorLayoutTest {

    @Test
    fun `the world is drawn only between the docked panels, and input does not cross the view's edge`() {
        GlAvailabilityHere.require()

        val registry = RenderRegistry()
        val rig = CameraRig(
            netIds = NetIdIndex(),
            poses = { _, _, _, _ -> false },
            frameTime = registry.frameTime,
            worldWidth = WORLD_WIDTH,
            worldHeight = WORLD_HEIGHT,
        )
        registry.register(RenderPhase.PreRender, { rig })
        registry.register(RenderPhase.World, { resources -> Magenta(resources, rig) })
        val frames = FrameProbe()
        registry.overlay({ frames })
        val window = WindowProbe()
        registry.overlay({ window })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "udea-editor-layout", windowWidth = WIDTH, windowHeight = HEIGHT, renderWidth = WIDTH, renderHeight = HEIGHT),
            registry,
        )
        val fonts = editorFonts()
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            val views = EditorViews(backend.openSceneView(EditorCamera()), backend.openGameView())
            val session = glEditorSession(views)
            val ui = UiLayer(fonts, Size(WIDTH.toFloat(), HEIGHT.toFloat()))
            backend.show(ui)
            ui.show(session.window)
            val pointer = backend.onRenderThread { KoolPointer(ui) }
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)

            val scene = layout(EditorTab.Scene)
            assertSelfContained("Scene", window.read(frames), scene)

            backend.onRenderThread { session.show(EditorTab.Game) }
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
            val game = layout(EditorTab.Game)
            assertSelfContained("Game", window.read(frames), game)

            // The Game tab's pointer is the game's: just inside the view it arrives, just outside it
            // is the History panel's.
            val view = game.view
            backend.click(frames, pointer, Offset(view.right - INSIDE, view.centre.y))
            val inside = backend.onRenderThread { pointer.pressesSince(PointerInput.LEFT_BUTTON) }
            backend.click(frames, pointer, Offset(view.right + DIVIDER + INSIDE, view.centre.y))
            val outside = backend.onRenderThread { pointer.pressesSince(PointerInput.LEFT_BUTTON) }
            assertEquals(1, inside, "a click just inside the Game tab's edge did not reach the game")
            assertEquals(0, outside, "a click on the panel beside the Game tab reached the game")
        } finally {
            backend.close()
            fonts.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /** Where the view, the docked panels and the dividers are, read from a window with no GL. */
    private class Layout(val view: Rect, val panels: List<Rect>)

    private fun layout(tab: EditorTab): Layout {
        val twin = glEditorSession(EditorViews.detached())
        twin.show(tab)
        return uiTest(Size(WIDTH.toFloat(), HEIGHT.toFloat())) { twin.window.content() }.use { ui ->
            ui.settle()
            val view = ui.node(if (tab == EditorTab.Scene) EditorTags.SCENE_VIEW else EditorTags.GAME_VIEW).boundsInRoot
            val panels = ArrayList<Rect>()
            for (id in listOf(EditorTags.CREATE_PANEL, EditorTags.ASSET_PANEL, EditorTags.HISTORY_PANEL)) {
                panels += ui.node("debugwindow:$id").boundsInRoot
            }
            ui.root.forEach { node -> if (node.testTag?.startsWith("debugwindow:divider:") == true) panels += node.boundsInRoot }
            Layout(view, panels)
        }
    }

    private fun assertSelfContained(tab: String, shot: BufferedImage, layout: Layout) {
        save("editor-layout-${tab.lowercase()}.png", shot)
        val margin = layout.view.shrunkBy(-MARGIN_ACROSS, -MARGIN_DOWN)
        for (panel in layout.panels) {
            val under = count(shot, panel.shrunkBy(MARGIN_ACROSS, MARGIN_DOWN))
            assertEquals(0, under, "the $tab tab's world shows through the panel or divider at $panel")
        }
        assertTrue(count(shot, layout.view.shrunkBy(MARGIN_ACROSS, MARGIN_DOWN)) > 0, "the $tab tab drew no world in its view at ${layout.view}")

        // Nothing covers the view either: across its middle, the world runs from its left edge to its
        // right. That holds because the gap is narrower than the frame's shape, so the frame is fitted
        // to the view's width with bars above and below - checked first, or the edges prove nothing.
        assertTrue(
            layout.view.width / layout.view.height < WIDTH.toFloat() / HEIGHT,
            "the view ${layout.view} is wider than the frame's shape, so the world would not reach its sides",
        )
        val row = layout.view.centre.y.toInt()
        val columns = (0 until shot.width).filter { isWorld(shot.getRGB(it, row)) }
        assertEquals(layout.view.left, columns.first().toFloat(), EDGE, "something covers the $tab tab's left edge: the world starts at ${columns.first()}")
        assertEquals(layout.view.right, columns.last() + 1f, EDGE, "something covers the $tab tab's right edge: the world ends at ${columns.last()}")
        var outside = 0
        for (y in 0 until shot.height) for (x in 0 until shot.width) {
            if (!margin.contains(x + 0.5f, y + 0.5f) && isWorld(shot.getRGB(x, y))) outside++
        }
        assertEquals(0, outside, "the $tab tab's world reaches $outside pixels outside its view at ${layout.view}")
    }

    private fun Rect.shrunkBy(across: Float, down: Float) = Rect(left + across, top + down, right - across, bottom - down)

    private fun Rect.contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

    /** World-tinted pixels in [area]. */
    private fun count(shot: BufferedImage, area: Rect): Int {
        var found = 0
        for (y in area.top.toInt().coerceAtLeast(0) until area.bottom.toInt().coerceAtMost(shot.height)) {
            for (x in area.left.toInt().coerceAtLeast(0) until area.right.toInt().coerceAtMost(shot.width)) {
                if (isWorld(shot.getRGB(x, y))) found++
            }
        }
        return found
    }

    /**
     * Magenta, or anything magenta has been blended into: red and blue both above green. The editor's
     * own skin is greys and blues, with red never above green - (26, 31, 40) for a panel's body, and
     * (41, 29, 54) for the same body with the world showing through it.
     */
    private fun isWorld(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r - g > TINT && b - g > TINT
    }

    private fun save(name: String, image: BufferedImage) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        File(dir, name).writeBytes(out.toByteArray())
    }

    /** The world: magenta over [EXTENT] world units each way, far past anything a camera frames. */
    private class Magenta(private val resources: RenderResources, private val rig: CameraRig) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.begin(rig.projection)
            batch.fill(-EXTENT, -EXTENT, 2f * EXTENT, 2f * EXTENT, Rgba.of(1f, 0f, 1f))
            batch.end()
        }
    }

    /**
     * Reads the whole window back once, when asked. An overlay, so it runs at a defined point in the
     * frame; the default framebuffer then holds the previous frame fully drawn, interface included,
     * which is why [read] waits for frames after arming it. `glReadPixels` through LWJGL, as
     * `udea-render`'s `BackbufferProbe` does, because it reads whatever framebuffer is bound: the
     * window's.
     */
    private class WindowProbe : OverlaySystem {
        private val armed = AtomicBoolean(false)

        @Volatile
        private var last: BufferedImage? = null

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            if (!armed.compareAndSet(true, false)) return
            val width = target.width
            val height = target.height
            val pixels = ByteBuffer.allocateDirect(width * height * 4)
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until height) for (x in 0 until width) {
                // GL's rows count up from the bottom.
                val i = ((height - 1 - y) * width + x) * 4
                image.setRGB(
                    x,
                    y,
                    ((pixels.get(i).toInt() and 0xFF) shl 16) or ((pixels.get(i + 1).toInt() and 0xFF) shl 8) or (pixels.get(i + 2).toInt() and 0xFF),
                )
            }
            last = image
        }

        /** The window as it stands a few frames from now. */
        fun read(frames: FrameProbe): BufferedImage {
            last = null
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
            armed.set(true)
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
            return checkNotNull(last) { "the window was not read back" }
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val WORLD_WIDTH = 32f
        const val WORLD_HEIGHT = 18f

        const val EXTENT = 10_000f

        /** How far red and blue must each stand above green for a pixel to count as the world's. */
        const val TINT = 4

        /**
         * Pixels allowed either side of an edge read from the headless twin: see the class KDoc. Down
         * the window only - across it, where the panels and the view are depends on the window's
         * width and nothing a font decides, so there one pixel of rounding is all that is allowed.
         */
        const val MARGIN_DOWN = 8f
        const val MARGIN_ACROSS = 1f

        /** How far the world's first and last column may sit from the view's edge: rounding, and a blended pixel. */
        const val EDGE = 2f

        /** ComposeGL's divider between two docked panes, in pixels at this window's scale. */
        const val DIVIDER = 6f

        /** How far inside or outside the view's edge a click lands, in pixels. */
        const val INSIDE = 12f
    }
}
