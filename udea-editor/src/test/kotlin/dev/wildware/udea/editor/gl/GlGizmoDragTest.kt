package dev.wildware.udea.editor.gl

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.editor.BodyPlacement
import dev.wildware.udea.editor.EditorGizmos
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorSpawn
import dev.wildware.udea.editor.EditorToolLoop
import dev.wildware.udea.editor.EditorTools
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.editor.FixtureGizmos
import dev.wildware.udea.editor.GizmoAxes
import dev.wildware.udea.editor.GizmoPreferences
import dev.wildware.udea.editor.editorFonts
import dev.wildware.udea.editor.fixtureComponents
import dev.wildware.udea.editor.gizmo.HandlePainter
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
import dev.wildware.udea.render.view.ViewPoint
import org.lwjgl.glfw.GLFW
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #236 through a real Kool backend and the real mouse and keyboard: **dragging each built-in
 * handle changes the entity by the expected world amount in one undo entry, and Escape mid-drag puts
 * it back**; a drag with two selected moves both as one edit; grid snapping rounds to the step and
 * Ctrl bypasses it; local axes turn the arrows with the entity.
 *
 * The world is three physics bodies - one plain, one with a [Box], one with a [Circle] - with the
 * fixture's gizmos, which are written against the public gizmo API alone and call the built-ins
 * ([FixtureGizmos]). [Bodies] draws each as a square with a nub on the side it faces, so a turn
 * shows. The mouse and keys go in through Kool's own GLFW callbacks. What is asserted is the world
 * and the `editor` author's history as `editor.history` lists it ([EditorToolLoop]).
 *
 * The window is read back while each handle is held and saved beside the other GL reports.
 */
class GlGizmoDragTest {

    @Test
    fun `each built-in handle dragged with the real mouse changes the entity by the drag in one undo entry, and Escape puts it back`() {
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
        var bodies: Bodies? = null
        registry.register(RenderPhase.World, { resources -> Bodies(resources, rig).also { bodies = it } })
        val frames = FrameProbe()
        registry.overlay({ frames })
        val window = WindowProbe()
        registry.overlay({ window })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "udea-editor-gizmos", windowWidth = WIDTH, windowHeight = HEIGHT, renderWidth = WIDTH, renderHeight = HEIGHT),
            registry,
        )
        val fonts = editorFonts()
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            val loop = EditorToolLoop(host, fixtureComponents())
            val views = EditorViews(backend.openSceneView(EditorCamera()), backend.openGameView())
            val preferences = GizmoPreferences()
            val session = EditorSession(
                tools = EditorTools(loop.bridge, loop.author),
                tick = { Tick(0) },
                paused = { true },
                spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
                views = views,
                gizmos = EditorGizmos(FixtureGizmos, host.world, host.ctx[CoreModule.NET_IDS], loop.components, BodyPlacement, preferences),
            )
            backend.drive { delta ->
                host.frame(delta)
                loop.pump()
                session.frame()
            }
            val netIds = host.ctx[CoreModule.NET_IDS]
            fun place(x: Float, y: Float, box: Box? = null, circle: Circle? = null): NetId = backend.onRenderThread {
                netIds.allocate(
                    host.world.entity {
                        it += PhysicsBody(x = x, y = y)
                        if (box != null) it += box
                        if (circle != null) it += circle
                    },
                )
            }
            val plain = place(-SPREAD, 0f)
            val crate = place(0f, 0f, box = Box(halfWidth = 3f, halfHeight = 2f))
            val ball = place(SPREAD, 0f, circle = Circle(radius = 1.5f))
            backend.onRenderThread { checkNotNull(bodies).of = { host.world.bodies(netIds, listOf(plain, crate, ball)) } }

            val ui = UiLayer(fonts, Size(WIDTH.toFloat(), HEIGHT.toFloat()))
            backend.show(ui)
            ui.show(session.window)
            val pointer = backend.onRenderThread { KoolPointer(ui) }
            backend.onRenderThread { KoolKeyboard(ui) }
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)

            // Zoomed in twice over the game camera's view, which the Scene tab takes on its first frame,
            // so the bodies are drawn at a size beside which the handles read.
            backend.onRenderThread { views.camera.zoomAt(ZOOM, views.scene.width / 2f, views.scene.height / 2f) }
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)

            val view = sceneViewOn(WIDTH, HEIGHT)
            // Values, read once: the component itself goes on changing under a later drag.
            fun body(id: NetId): Pose = backend.onRenderThread {
                with(host.world) { checkNotNull(netIds.resolveOrNull(id))[PhysicsBody].let { Pose(it.x, it.y, it.angle) } }
            }
            fun box(id: NetId): Pair<Float, Float> = backend.onRenderThread {
                with(host.world) { checkNotNull(netIds.resolveOrNull(id))[Box].let { it.halfWidth to it.halfHeight } }
            }
            fun radius(id: NetId): Float = backend.onRenderThread { with(host.world) { checkNotNull(netIds.resolveOrNull(id))[Circle].radius } }
            fun history(): List<String> = backend.onRenderThread { loop.history() }
            fun select(ids: List<NetId>) {
                backend.onRenderThread { loop.select(ids) }
                awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
            }
            // Where a world point is on screen, and a point [dx], [dy] view pixels from it.
            fun screen(x: Float, y: Float, dx: Float = 0f, dy: Float = 0f): Offset = backend.onRenderThread {
                val at = ViewPoint()
                views.camera.project(x, y, 0f, at)
                screenOfView(views, view, ViewPoint(at.x + dx, at.y + dy))
            }
            // How many world units one view pixel is in the Scene tab.
            val unitsPerPixel = backend.onRenderThread { 1f / views.camera.projection.scaleX }
            var edits = 0

            // --- move: the X arrow, held to X ---------------------------------------------------------
            select(listOf(plain))
            val from = body(plain)
            backend.drag(frames, screen(from.x, from.y, ARROW_GRIP), screen(from.x, from.y, ARROW_GRIP + 40f, 25f)) {
                saveReport("issue236-gl-move-x-arrow.png", window.read(frames))
            }
            assertNear(from.x + 40f * unitsPerPixel, body(plain).x, "the X arrow did not move the body by the drag", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertEquals(from.y, body(plain).y, "the X arrow moved the body off X")
            assertEquals(List(++edits) { COMMIT }, history(), "a move is not one undo entry")

            // --- rotate: the ring -------------------------------------------------------------------------
            val turnFrom = body(plain)
            backend.drag(
                frames,
                screen(turnFrom.x, turnFrom.y, HandlePainter.RING_RADIUS, 0f),
                screen(turnFrom.x, turnFrom.y, 0f, HandlePainter.RING_RADIUS),
            ) {
                saveReport("issue236-gl-rotate-ring.png", window.read(frames))
            }
            assertNear((PI / 2).toFloat(), body(plain).angle, "the ring did not turn the body a quarter", tolerance = ANGLE_TOLERANCE)
            assertEquals(List(++edits) { COMMIT }, history(), "a turn is not one undo entry")

            // --- resize: a box corner -------------------------------------------------------------------------
            // The fixture's gizmo hands the box's half extents to the built-in as its width and height,
            // so the corner it puts at +X +Y is half of each out: (1.5, 1).
            select(listOf(crate))
            val corner = screen(1.5f, 1f)
            backend.drag(frames, corner, Offset(corner.x + 20f * view.width / views.scene.width, corner.y)) {
                saveReport("issue236-gl-resize-corner.png", window.read(frames))
            }
            assertNear(3f + 2f * 20f * unitsPerPixel, box(crate).first, "the corner did not widen the box by twice its move", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertNear(2f, box(crate).second, "a sideways corner drag changed the height", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertEquals(List(++edits) { COMMIT }, history(), "a resize is not one undo entry")

            // --- radius: the rim grip -------------------------------------------------------------------------
            select(listOf(ball))
            backend.drag(frames, screen(SPREAD + 1.5f, 0f), screen(SPREAD + 1.5f, 0f, 25f)) {
                saveReport("issue236-gl-radius-rim.png", window.read(frames))
            }
            assertNear(1.5f + 25f * unitsPerPixel, radius(ball), "the rim did not follow the drag", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertEquals(List(++edits) { COMMIT }, history(), "a radius drag is not one undo entry")

            // --- Escape mid-drag -------------------------------------------------------------------------
            val still = body(ball)
            backend.drag(frames, screen(still.x, still.y), screen(still.x, still.y, 70f, -40f), release = false)
            assertTrue(abs(body(ball).x - still.x) > 0f, "the drag was not live before Escape")
            backend.key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS)
            backend.key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_RELEASE)
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
            backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
            assertEquals(still.x, body(ball).x, "Escape did not put the body back")
            assertEquals(still.y, body(ball).y, "Escape did not put the body back")
            assertEquals(List(edits) { COMMIT }, history(), "a cancelled drag filed an undo entry")

            // --- two selected: one drag, one edit ----------------------------------------------------------
            select(listOf(plain, ball))
            val left = body(plain)
            val right = body(ball)
            val centre = Pair((left.x + right.x) / 2f, (left.y + right.y) / 2f)
            backend.drag(frames, screen(centre.first, centre.second), screen(centre.first, centre.second, 20f, -40f)) {
                saveReport("issue236-gl-two-selected-move.png", window.read(frames))
            }
            assertNear(left.x + 20f * unitsPerPixel, body(plain).x, "the first body did not move with the drag", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertNear(left.y - 40f * unitsPerPixel, body(plain).y, "the first body did not move with the drag", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertNear(right.x + 20f * unitsPerPixel, body(ball).x, "the second body did not move with the drag", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertNear(right.y - 40f * unitsPerPixel, body(ball).y, "the second body did not move with the drag", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertEquals(List(++edits) { COMMIT }, history(), "a drag of two is not one undo entry")

            // --- grid snapping, and Ctrl to bypass it ---------------------------------------------------------
            // On the plain body: the crate's side grip sits on its own X arrow, and would take the press.
            select(listOf(plain))
            backend.onRenderThread {
                preferences.gridSnap = true
                preferences.gridStep = GRID
            }
            val unsnapped = body(plain)
            backend.drag(frames, screen(unsnapped.x, unsnapped.y, ARROW_GRIP), screen(unsnapped.x, unsnapped.y, ARROW_GRIP + 37f)) {
                saveReport("issue236-gl-grid-snap.png", window.read(frames))
            }
            val snapped = body(plain).x
            assertEquals(unsnapped.y, body(plain).y, "the X arrow snapped the body's y as well")
            assertNear(round((unsnapped.x + 37f * unitsPerPixel) / GRID) * GRID, snapped, "the move was not rounded to the grid step", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertNear(round(snapped / GRID) * GRID, snapped, "the snapped position is not on the grid")
            backend.holding(frames, GLFW.GLFW_KEY_LEFT_CONTROL) {
                backend.drag(frames, screen(snapped, body(plain).y, ARROW_GRIP), screen(snapped, body(plain).y, ARROW_GRIP + 37f)) {
                    saveReport("issue236-gl-grid-ctrl-bypass.png", window.read(frames))
                }
            }
            assertNear(snapped + 37f * unitsPerPixel, body(plain).x, "Ctrl did not write the move exactly", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            edits += 2
            assertEquals(List(edits) { COMMIT }, history())

            // --- local axes -----------------------------------------------------------------------------------
            // The first body was turned a quarter above: in local axes its X arrow points up the screen.
            backend.onRenderThread {
                preferences.gridSnap = false
                preferences.axes = GizmoAxes.Local
            }
            val turned = body(plain)
            backend.drag(frames, screen(turned.x, turned.y, 0f, ARROW_GRIP), screen(turned.x, turned.y, 25f, ARROW_GRIP + 50f)) {
                saveReport("issue236-gl-local-axes.png", window.read(frames))
            }
            assertNear(turned.x, body(plain).x, "the local X arrow moved the body across its own X", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertNear(turned.y + 50f * unitsPerPixel, body(plain).y, "the local X arrow did not move the body along its own X", tolerance = SCREEN_TOLERANCE * unitsPerPixel)
            assertEquals(List(++edits) { COMMIT }, history())
        } finally {
            backend.close()
            fonts.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun assertNear(expected: Float, actual: Float, message: String, tolerance: Float = TOLERANCE) {
        assertTrue(abs(expected - actual) <= tolerance, "$message: expected $expected, was $actual")
    }

    /** Each listed body where it is now, with its box's or circle's size when it has one. */
    private fun com.github.quillraven.fleks.World.bodies(netIds: NetIdIndex, ids: List<NetId>): List<Drawn> = ids.mapNotNull { id ->
        val entity = netIds.resolveOrNull(id) ?: return@mapNotNull null
        val body = entity[PhysicsBody]
        val box = entity.getOrNull(Box)
        val circle = entity.getOrNull(Circle)
        val half = when {
            // As wide and tall as the fixture's gizmo says: it treats the half extents as the sizes.
            box != null -> Pair(box.halfWidth / 2f, box.halfHeight / 2f)
            circle != null -> Pair(circle.radius * INSCRIBED, circle.radius * INSCRIBED)
            else -> Pair(BODY_HALF, BODY_HALF)
        }
        Drawn(body.x, body.y, body.angle, half.first, half.second)
    }

    /** Where a body is and which way it faces, as read at one moment. */
    private data class Pose(val x: Float, val y: Float, val angle: Float)

    /** One body as [Bodies] draws it. */
    private class Drawn(val x: Float, val y: Float, val angle: Float, val halfWidth: Float, val halfHeight: Float)

    /** Draws every body as a square, with a nub on the side it faces. Render thread only. */
    private class Bodies(private val resources: RenderResources, private val rig: CameraRig) : RenderSystem {
        var of: () -> List<Drawn> = { emptyList() }

        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.begin(rig.projection)
            try {
                for (body in of()) {
                    batch.fill(body.x - body.halfWidth, body.y - body.halfHeight, 2 * body.halfWidth, 2 * body.halfHeight, BODY)
                    val nubX = body.x + cos(body.angle) * (body.halfWidth + NUB)
                    val nubY = body.y + sin(body.angle) * (body.halfHeight + NUB)
                    batch.fill(nubX - NUB, nubY - NUB, 2 * NUB, 2 * NUB, FACING)
                }
            } finally {
                batch.end()
            }
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val WORLD_WIDTH = 32f
        const val WORLD_HEIGHT = 18f

        /** How much of the game camera's world the Scene tab shows: half of it, each way. */
        const val ZOOM = 0.5f

        /** How far apart the bodies stand, in world units. */
        const val SPREAD = 5f

        const val BODY_HALF = 0.6f
        const val NUB = 0.25f

        /** How much of a circle's radius the square drawn for it reaches: it sits inside the rim. */
        const val INSCRIBED = 0.7f

        /** The grid step, in world units. */
        const val GRID = 1f

        /** Where on an arrow a person grabs it, in view pixels out from the body: along the shaft. */
        const val ARROW_GRIP = 40f

        const val COMMIT = "editor.commit_edit"

        /** Radians a turn may land off by: a few pixels on a ring of [HandlePainter.RING_RADIUS]. */
        const val ANGLE_TOLERANCE = 0.05f

        /** Floats that went through no pixels. */
        const val TOLERANCE = 1e-3f

        /**
         * Pixels a drag may land off by: the Scene tab's rectangle is read from a headless twin whose
         * font differs by a few pixels down, and every move is rounded to a whole pixel on the way in.
         */
        const val SCREEN_TOLERANCE = 3f

        val BODY: Rgba = Rgba.of(0.3f, 0.45f, 0.7f)
        val FACING: Rgba = Rgba.of(0.95f, 0.6f, 0.2f)
    }
}
