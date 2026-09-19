package dev.wildware.udea.editor.gl

import de.fabmax.kool.input.PointerInput
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorSpawn
import dev.wildware.udea.editor.EditorTab
import dev.wildware.udea.editor.EditorTags
import dev.wildware.udea.editor.EditorTools
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.editor.editorFonts
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.CaptureResult
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.kool.KoolPointer
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.GizmoLayer
import dev.wildware.udea.render.view.ViewPoint
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.lwjgl.glfw.GLFW
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #234 through the real editor window on a real Kool backend: **with the overlay on, the Game
 * tab draws gizmos, and a click on one still reaches the game, never the gizmo.**
 *
 * The mouse goes in through Kool's own GLFW callbacks, as in `udea-render`'s `GlKoolPointerTest`, and
 * the game's side is a `KoolPointer` over the window's `UiLayer` - the pointer a game's intents are
 * sampled from, which hears a press only when the interface did not use it.
 *
 * One placeholder gizmo, a large square over world (0, 0), is set on both tabs; the game camera and the
 * Scene tab's camera start on the same framing, so it sits in the same place in both. The Scene tab is
 * the control: the same click there is the gizmo's and never the game's. Without it, "the game got the
 * click and the gizmo did not" would also be what a click beside the gizmo looks like.
 *
 * And, with gizmos drawn in both tabs, the capturable frame - what `render.screenshot` files - holds
 * none of the gizmo's colour while the Game tab's own picture does.
 */
class GlEditorTabsTest {

    @Test
    fun `a click on a gizmo in the Game tab reaches the game, and the same click in the Scene tab is the gizmo's`() {
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
        val frames = FrameProbe()
        registry.overlay({ frames })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "udea-editor-tabs", windowWidth = WIDTH, windowHeight = HEIGHT, renderWidth = WIDTH, renderHeight = HEIGHT),
            registry,
        )
        val fonts = editorFonts()
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            val views = EditorViews(backend.openSceneView(EditorCamera()), backend.openGameView())
            val gizmo = Square()
            backend.onRenderThread {
                views.scene.gizmos = gizmo
                views.game.gizmos = gizmo
            }
            val session = session(views)
            val ui = UiLayer(fonts, Size(WIDTH.toFloat(), HEIGHT.toFloat()))
            backend.show(ui)
            ui.show(session.window)
            val pointer = backend.onRenderThread { KoolPointer(ui) }
            val at = viewCentre()
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)

            // 1. The Scene tab, the control: a click on the gizmo is the gizmo's and not the game's.
            click(backend, frames, pointer, at)
            val sceneGizmo = gizmo.presses.get()
            val sceneGame = backend.onRenderThread { pointer.pressesSince(PointerInput.LEFT_BUTTON) }
            assertEquals(1, sceneGizmo, "a click on the Scene tab's gizmo did not reach it, so the click below proves nothing")
            assertEquals(0, sceneGame, "a click on the Scene tab reached the game")

            // 2. The Game tab with its overlay on: the gizmo is drawn, and the same click is the game's.
            backend.onRenderThread {
                session.show(EditorTab.Game)
                session.showGameGizmos(true)
            }
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
            click(backend, frames, pointer, at)
            val gameGizmo = gizmo.presses.get()
            val gameGame = backend.onRenderThread { pointer.pressesSince(PointerInput.LEFT_BUTTON) }
            assertEquals(1, gameGame, "a click on a gizmo in the Game tab did not reach the game")
            assertEquals(1, gameGizmo, "a click in the Game tab reached a gizmo")

            // 3. Gizmos drawn in both tabs: the Game tab's picture holds one, the capturable frame none.
            val (captured, shown) = backend.onRenderThread {
                backend.pipeline!!.capture!!.submit(CaptureRequest()) to views.game.capture()
            }
            val capture = await(captured)
            val gameTab = await(shown)
            save("editor-tabs-capture.png", capture.bytes)
            save("editor-tabs-game-tab.png", gameTab.bytes)
            assertTrue(count(gameTab.bytes) > 0, "the Game tab's overlay drew no gizmo")
            assertEquals(0, count(capture.bytes), "render.screenshot holds gizmo pixels")
        } finally {
            backend.close()
            fonts.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun session(views: EditorViews): EditorSession = EditorSession(
        tools = EditorTools(AgentBridge(), AgentSessions().intern("editor")),
        tick = { Tick(0) },
        paused = { true },
        spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
        views = views,
    )

    /**
     * The middle of the tab's page on the screen, which is where the middle of its view is drawn.
     *
     * Read from the same window laid out with no GL, at the same size: the page spans the window's
     * width, so its middle across is exact, and its middle down is off by whatever the bundled font's
     * line height differs from the headless one's - a few pixels, where the gizmo is hundreds tall.
     */
    private fun viewCentre(): Offset {
        val twin = session(EditorViews.detached())
        return uiTest(Size(WIDTH.toFloat(), HEIGHT.toFloat())) { twin.window.content() }.use { ui ->
            ui.node(EditorTags.SCENE_VIEW).boundsInRoot.centre
        }
    }

    /** Moves to [at], presses and releases the left button, and waits for Kool and the interface. */
    private fun click(backend: KoolBackend, frames: FrameProbe, pointer: KoolPointer, at: Offset) {
        backend.onRenderThread { pointer.endSample() }
        backend.moveTo(at.x.toDouble(), at.y.toDouble())
        awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
        backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
        awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
        backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
        awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
    }

    private fun awaitFrames(probe: FrameProbe, target: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (probe.count.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(probe.count.get() >= target, "the render thread stopped drawing")
    }

    /**
     * Moves the mouse on Kool's own GLFW cursor callback, twice, as `GlKoolPointerTest` does and for
     * its reason: Kool holds back the move that starts a drag.
     */
    private fun KoolBackend.moveTo(x: Double, y: Double) = onRenderThread {
        val window = currentWindow()
        val callback = checkNotNull(GLFW.glfwSetCursorPosCallback(window, null)) {
            "Kool installed no GLFW cursor callback, so this test would be moving nothing"
        }
        try {
            callback.invoke(window, x, y)
            callback.invoke(window, x, y)
        } finally {
            GLFW.glfwSetCursorPosCallback(window, callback)
        }
    }

    /** Presses or releases a mouse button on Kool's own GLFW callback. */
    private fun KoolBackend.button(button: Int, action: Int) = onRenderThread {
        val window = currentWindow()
        val callback = checkNotNull(GLFW.glfwSetMouseButtonCallback(window, null)) {
            "Kool installed no GLFW mouse button callback, so this test would be clicking nothing"
        }
        try {
            callback.invoke(window, button, action, NO_MODIFIERS)
        } finally {
            GLFW.glfwSetMouseButtonCallback(window, callback)
        }
    }

    private fun currentWindow(): Long {
        val window = GLFW.glfwGetCurrentContext()
        check(window != 0L) { "no GLFW window is current on the render thread" }
        return window
    }

    private fun await(result: Deferred<CaptureResult>): CaptureResult =
        runBlocking { withTimeout(CAPTURE_TIMEOUT_MILLIS) { result.await() } }

    /** Pixels of the gizmo's colour in a PNG. */
    private fun count(png: ByteArray): Int {
        val image = checkNotNull(ImageIO.read(ByteArrayInputStream(png))) { "the captured bytes are not a decodable image" }
        var found = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) and RGB == YELLOW) found++
        return found
    }

    private fun save(name: String, png: ByteArray) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        File(dir, name).writeBytes(png)
    }

    /** Counts frames. */
    private class FrameProbe : OverlaySystem {
        val count: AtomicInteger = AtomicInteger()

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            count.incrementAndGet()
        }
    }

    /** The placeholder gizmo: a yellow square [SIZE] view pixels across over world (0, 0), counting presses. */
    private class Square : GizmoLayer {
        private val at = ViewPoint()
        val presses = AtomicInteger()

        override fun draw(canvas: GizmoCanvas) {
            if (canvas.project(0f, 0f, 0f, at)) canvas.fill(at.x - SIZE / 2f, at.y - SIZE / 2f, SIZE, SIZE, Rgba.of(1f, 1f, 0f))
        }

        override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean {
            if (!canvas.project(0f, 0f, 0f, at)) return false
            val hit = abs(viewX - at.x) <= SIZE / 2f && abs(viewY - at.y) <= SIZE / 2f
            if (hit) presses.incrementAndGet()
            return hit
        }

        companion object {
            const val SIZE = 240f
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val WORLD_WIDTH = 32f
        const val WORLD_HEIGHT = 18f

        const val RGB = 0xFFFFFF
        const val YELLOW = 0xFFFF00

        /** GLFW passes modifiers with a mouse button, and Kool's mapping reads none. */
        const val NO_MODIFIERS = 0

        /** Frames from a GLFW event to the interface's verdict on it: see `GlKoolPointerTest`. */
        const val SETTLE_FRAMES = 4

        const val CAPTURE_TIMEOUT_MILLIS = 10_000L
    }
}
