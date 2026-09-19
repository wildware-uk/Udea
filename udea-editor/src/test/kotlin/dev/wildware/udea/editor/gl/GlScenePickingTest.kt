package dev.wildware.udea.editor.gl

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorSpawn
import dev.wildware.udea.editor.EditorToolLoop
import dev.wildware.udea.editor.EditorTools
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.editor.editorFonts
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.kool.KoolKeyboard
import dev.wildware.udea.render.kool.KoolPointer
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.PickBounds
import dev.wildware.udea.render.view.PickSink
import org.lwjgl.glfw.GLFW
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #235 through a real Kool backend and the real mouse and keyboard: **click, Shift-click and
 * box select in the Scene tab give the expected `editor.selection`**, and each has a miss beside it
 * that selects nothing.
 *
 * The world is three coloured squares drawn by [Tiles], a render system of this test's own that
 * implements `PickBounds` and nothing else the engine knows about - which is the whole of what a
 * game's own render system does to make its entities pickable. The mouse and Shift go in through
 * Kool's own GLFW callbacks. What is asserted is the selection the real `EditorToolset` holds for the
 * `editor` author, read with `editor.selection` as an agent reads it ([EditorToolLoop]).
 *
 * The window is read back at the notable moments - a square picked, two picked, the box mid-drag -
 * and saved beside the other GL reports for a person to look at.
 */
class GlScenePickingTest {

    @Test
    fun `click, Shift-click and box select with the real mouse give the expected selection, and misses select nothing`() {
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
        var tiles: Tiles? = null
        registry.register(RenderPhase.World, { resources -> Tiles(resources, rig).also { tiles = it } })
        val frames = FrameProbe()
        registry.overlay({ frames })
        val window = WindowProbe()
        registry.overlay({ window })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "udea-editor-picking", windowWidth = WIDTH, windowHeight = HEIGHT, renderWidth = WIDTH, renderHeight = HEIGHT),
            registry,
        )
        val fonts = editorFonts()
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            val loop = EditorToolLoop(host)
            val views = EditorViews(backend.openSceneView(EditorCamera()), backend.openGameView())
            val session = EditorSession(
                tools = EditorTools(loop.bridge, loop.author),
                tick = { Tick(0) },
                paused = { true },
                spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
                views = views,
            )
            // As a launcher drives an editor, with the tools answering between the game's frame and
            // the editor's.
            backend.drive { delta ->
                host.frame(delta)
                loop.pump()
                session.frame()
            }
            // The blue square, above the other two, is what the box must leave out.
            val (left, right) = backend.onRenderThread {
                val placed = listOf(loop.spawn(), loop.spawn(), loop.spawn())
                checkNotNull(tiles).squares = listOf(
                    Tile(placed[0], -SPREAD, 0f, RED),
                    Tile(placed[1], SPREAD, 0f, GREEN),
                    Tile(placed[2], 0f, SPREAD, BLUE),
                )
                placed
            }
            val ui = UiLayer(fonts, Size(WIDTH.toFloat(), HEIGHT.toFloat()))
            backend.show(ui)
            ui.show(session.window)
            val pointer = backend.onRenderThread { KoolPointer(ui) }
            backend.onRenderThread { KoolKeyboard(ui) }
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)

            val view = sceneViewOn(WIDTH, HEIGHT)
            fun screen(x: Float, y: Float): Offset = backend.onRenderThread { screenOfWorld(views, view, x, y) }
            fun selection(): Set<NetId> = backend.onRenderThread { loop.selection() }.toSet()
            val empty = screen(-SPREAD, -SPREAD)

            // A click on nothing selects nothing.
            backend.click(frames, pointer, empty)
            assertEquals(emptySet(), selection(), "a click on empty space selected something")

            // A click on a square selects it, and only it.
            backend.click(frames, pointer, screen(-SPREAD, 0f))
            assertEquals(setOf(left), selection(), "a click on the red square")
            saveReport("issue235-gl-click-selects-red.png", window.read(frames))

            // Shift-click adds, and Shift-click on a selected square takes it out.
            backend.holding(frames, GLFW.GLFW_KEY_LEFT_SHIFT) { backend.click(frames, pointer, screen(SPREAD, 0f)) }
            assertEquals(setOf(left, right), selection(), "Shift-click on the green square did not add it")
            saveReport("issue235-gl-shift-click-adds-green.png", window.read(frames))
            backend.holding(frames, GLFW.GLFW_KEY_LEFT_SHIFT) { backend.click(frames, pointer, empty) }
            assertEquals(setOf(left, right), selection(), "a Shift-click on nothing changed the selection")
            backend.holding(frames, GLFW.GLFW_KEY_LEFT_SHIFT) { backend.click(frames, pointer, screen(-SPREAD, 0f)) }
            assertEquals(setOf(right), selection(), "Shift-click on the selected red square did not take it out")

            // A box over nothing selects nothing; a box over two squares selects those two.
            backend.drag(frames, empty, screen(-SPREAD - 2 * HALF, -SPREAD / 2f))
            assertEquals(emptySet(), selection(), "a box over nothing selected something")
            backend.drag(frames, empty, screen(SPREAD + 2 * HALF, 0f)) {
                saveReport("issue235-gl-box-mid-drag.png", window.read(frames))
            }
            assertEquals(setOf(left, right), selection(), "the box over the red and green squares")
            saveReport("issue235-gl-box-selected-two.png", window.read(frames))
        } finally {
            backend.close()
            fonts.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /** One entity drawn as a square [HALF] world units either side of ([x], [y]). */
    private class Tile(val entity: NetId, val x: Float, val y: Float, val colour: Rgba)

    /**
     * A game's own render system, as far as the editor can tell: it draws squares, and it says where
     * it drew them. Render thread only.
     */
    private class Tiles(private val resources: RenderResources, private val rig: CameraRig) : RenderSystem, PickBounds {
        var squares: List<Tile> = emptyList()

        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.begin(rig.projection)
            try {
                for (tile in squares) batch.fill(tile.x - HALF, tile.y - HALF, 2 * HALF, 2 * HALF, tile.colour)
            } finally {
                batch.end()
            }
        }

        override fun reportPickBounds(out: PickSink) {
            for (tile in squares) out.rect(tile.entity, tile.x - HALF, tile.y - HALF, tile.x + HALF, tile.y + HALF)
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val WORLD_WIDTH = 32f
        const val WORLD_HEIGHT = 18f

        /** How far each square's centre is from the world's origin, in world units. */
        const val SPREAD = 4f

        /** Half a square's side, in world units. */
        const val HALF = 1.2f

        val RED: Rgba = Rgba.of(0.85f, 0.2f, 0.2f)
        val GREEN: Rgba = Rgba.of(0.2f, 0.75f, 0.3f)
        val BLUE: Rgba = Rgba.of(0.25f, 0.4f, 0.9f)
    }
}
