package dev.wildware.udea.editor

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.editor.gizmo.HandlePainter
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.WorldViewport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dragging the built-in 2D gizmos through the editor window's own pointer, with no GL (issue #236).
 *
 * The pointer goes in through the window as a person's does, and what is asserted is the world an
 * agent reads, and the `editor` author's undo history as `editor.history` lists it: every drag is
 * one `editor.begin_edit` session, so it is one undo entry however many moves it took, and Escape is
 * `editor.cancel_edit`, which puts every value back and files nothing. The gizmos are the fixture's,
 * written against the public API alone ([FixtureGizmos]), over `udea-core`'s physics components.
 *
 * `GlGizmoDragTest` does the same through a real Kool backend and the real mouse.
 */
class SceneGizmoDragTest {

    private val host = GameHost(RenderMode.Headless, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()))

    private val loop = EditorToolLoop(host, fixtureComponents())

    /** The Scene tab's camera, fitted up front so the entities can be placed in view pixels. */
    private val camera = EditorCamera().also { it.fit(VIEW_WIDTH, VIEW_HEIGHT) }

    private val preferences = GizmoPreferences()

    private val session = EditorSession(
        tools = EditorTools(loop.bridge, loop.author),
        tick = { Tick(0) },
        paused = { true },
        spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
        views = EditorViews(
            scene = WorldViewport.detached(camera, VIEW_WIDTH, VIEW_HEIGHT),
            game = WorldViewport.detached(null, VIEW_WIDTH, VIEW_HEIGHT),
        ),
        gizmos = EditorGizmos(FixtureGizmos, host.world, host.ctx[CoreModule.NET_IDS], loop.components, BodyPlacement, preferences),
    )

    // --- each handle, one undo entry, and Escape -------------------------------------------------

    @Test
    fun `the X arrow moves the entity along X only, by the drag, as one undo entry`() {
        val body = body(atX = 0f, atY = 0f)
        open { ui ->
            val before = bodyOf(body).y
            // Grabbed on the arrow's shaft, then dragged right and a little up: the arrow holds it to X.
            drag(ui, at(ARROW_GRIP, 0f), at(ARROW_GRIP + 30f, 12f))

            assertNear(worldX(30f), bodyOf(body).x, "the X arrow did not move the entity by the drag")
            assertEquals(before, bodyOf(body).y, "the X arrow moved the entity off X")
            assertEquals(listOf(COMMIT), loop.history(), "a drag is one undo entry")
        }
    }

    @Test
    fun `the free square moves the entity by the whole drag`() {
        val body = body(atX = 0f, atY = 0f)
        open { ui ->
            drag(ui, at(0f, 0f), at(-25f, 40f))

            assertNear(worldX(-25f), bodyOf(body).x, "the square did not move the entity across")
            assertNear(worldY(40f), bodyOf(body).y, "the square did not move the entity up")
            assertEquals(listOf(COMMIT), loop.history())
        }
    }

    @Test
    fun `the ring turns the entity by the angle the drag went round it`() {
        val body = body(atX = 0f, atY = 0f, angle = 0.25f)
        open { ui ->
            // From east of the entity to north of it, on the ring: a quarter turn anticlockwise.
            drag(ui, at(HandlePainter.RING_RADIUS, 0f), at(0f, HandlePainter.RING_RADIUS))

            assertNear(0.25f + (PI / 2).toFloat(), bodyOf(body).angle, "the ring did not turn the entity a quarter")
            assertEquals(listOf(COMMIT), loop.history())
        }
    }

    @Test
    fun `a box corner resizes both sides and an edge only its own, each drag one undo entry`() {
        val body = body(atX = 0f, atY = 0f, box = Box(halfWidth = units(200f), halfHeight = units(120f)))
        open { ui ->
            // The corner at +X +Y is half the box's two sizes out: (100, 60) view pixels.
            drag(ui, at(100f, 60f), at(110f, 75f))
            assertNear(units(220f), boxOf(body).halfWidth, "the corner did not widen the box by twice its move")
            assertNear(units(150f), boxOf(body).halfHeight, "the corner did not heighten the box by twice its move")

            // The left side's middle, dragged out and up: only the width changes.
            drag(ui, at(-110f, 0f), at(-120f, 30f))
            assertNear(units(240f), boxOf(body).halfWidth, "the edge did not widen the box")
            assertNear(units(150f), boxOf(body).halfHeight, "the edge changed the other side")
            assertEquals(listOf(COMMIT, COMMIT), loop.history(), "two drags are two undo entries")
        }
    }

    @Test
    fun `the rim grip sets the radius to its distance from the entity`() {
        val body = body(atX = 0f, atY = 0f, circle = Circle(radius = units(120f)))
        open { ui ->
            drag(ui, at(120f, 0f), at(150f, 0f))

            assertNear(units(150f), circleOf(body).radius, "the rim grip did not follow the drag")
            assertEquals(listOf(COMMIT), loop.history())
        }
    }

    @Test
    fun `Escape mid-drag puts the entity back and files nothing, and the release after it does nothing`() {
        val body = body(atX = 0f, atY = 0f)
        open { ui ->
            val start = bodyOf(body).x
            assertTrue(ui.press(screenOf(ui, at(0f, 0f))), "the Scene tab took no press")
            ui.dragTo(screenOf(ui, at(40f, 0f)))
            frames(ui)
            assertNear(worldX(40f), bodyOf(body).x, "the drag was not live before Escape")

            ui.keyDown(Key.Escape)
            ui.keyUp(Key.Escape)
            frames(ui)
            assertEquals(start, bodyOf(body).x, "Escape did not put the entity back")

            ui.dragTo(screenOf(ui, at(60f, 0f)))
            ui.release()
            frames(ui)
            assertEquals(start, bodyOf(body).x, "the drag went on after Escape")
            assertEquals(emptyList(), loop.history(), "a cancelled drag filed an undo entry")
        }
    }

    // --- several selected --------------------------------------------------------------------------

    @Test
    fun `with two selected the handles sit at their centre and one drag moves both as one edit`() {
        val left = body(atX = -60f, atY = 0f, select = false)
        val right = body(atX = 60f, atY = 40f, select = false)
        loop.select(listOf(left, right))
        open { ui ->
            // Their centre is (0, 20): the free square is there, and on neither of them.
            drag(ui, at(0f, 20f), at(15f, -10f))

            assertNear(worldX(-60f + 15f), bodyOf(left).x, "the left entity did not move with the drag")
            assertNear(worldY(-30f), bodyOf(left).y, "the left entity did not move with the drag")
            assertNear(worldX(60f + 15f), bodyOf(right).x, "the right entity did not move with the drag")
            assertNear(worldY(40f - 30f), bodyOf(right).y, "the right entity did not move with the drag")
            assertEquals(listOf(COMMIT), loop.history(), "a drag of two entities is one undo entry")
        }
    }

    // --- snapping and axes ---------------------------------------------------------------------------

    @Test
    fun `grid snapping rounds the position to the step, and Ctrl held writes it exactly`() {
        val body = body(atX = 0f, atY = 0f)
        val step = units(20f)
        preferences.gridSnap = true
        preferences.gridStep = step
        open { ui ->
            drag(ui, at(0f, 0f), at(33f, 0f))
            val snapped = bodyOf(body).x
            assertOnStep(snapped, step, "the position was not snapped to the grid")
            assertNear(kotlin.math.round(worldX(33f) / step) * step, snapped, "the position was not snapped to the nearest step")

            ui.keyDown(Key.Control, Modifiers(Modifiers.CONTROL))
            val from = viewOf(snapped, bodyOf(body).y)
            drag(ui, from, ViewPoint(from.x + 33f, from.y))
            ui.keyUp(Key.Control)
            assertNear(snapped + units(33f), bodyOf(body).x, "Ctrl did not turn snapping off")
        }
    }

    @Test
    fun `angle snapping rounds the turn to the step`() {
        val body = body(atX = 0f, atY = 0f)
        preferences.angleSnap = true
        preferences.angleStepDegrees = 15f
        open { ui ->
            // About 50 degrees round the ring: the nearest 15 is 45.
            val to = (50.0 * PI / 180.0).toFloat()
            drag(ui, at(HandlePainter.RING_RADIUS, 0f), at(HandlePainter.RING_RADIUS * cos(to), HandlePainter.RING_RADIUS * sin(to)))

            assertNear((PI / 4).toFloat(), bodyOf(body).angle, "the turn was not snapped to 15 degrees")
        }
    }

    @Test
    fun `local axes turn the arrows with the entity, and world axes do not`() {
        val eighth = (PI / 4).toFloat()
        val body = body(atX = 0f, atY = 0f, angle = eighth)
        val diagonal = ARROW_GRIP / kotlin.math.sqrt(2f)
        preferences.axes = GizmoAxes.Local
        open { ui ->
            // The local X arrow points up and to the right. Dragged 30 pixels right, it moves the
            // entity along itself: 15 across and 15 up.
            drag(ui, at(diagonal, diagonal), at(diagonal + 30f, diagonal))
            assertNear(worldX(15f), bodyOf(body).x, "the local X arrow did not hold the drag to its own line")
            assertNear(worldY(15f), bodyOf(body).y, "the local X arrow did not hold the drag to its own line")
            assertEquals(listOf(COMMIT), loop.history())

            // With world axes nothing is drawn on that diagonal, so the same press is not a drag.
            preferences.axes = GizmoAxes.World
            val from = viewOf(bodyOf(body).x, bodyOf(body).y)
            drag(ui, ViewPoint(from.x + diagonal, from.y + diagonal), ViewPoint(from.x + diagonal + 30f, from.y + diagonal))
            assertNear(worldX(15f), bodyOf(body).x, "with world axes a press on the diagonal moved the entity")
            assertEquals(listOf(COMMIT), loop.history())
        }
    }

    // --- fixture -------------------------------------------------------------------------------------

    /** A body [atX], [atY] view pixels from the middle of the view, selected unless told not to be. */
    private fun body(
        atX: Float,
        atY: Float,
        angle: Float = 0f,
        box: Box? = null,
        circle: Circle? = null,
        select: Boolean = true,
    ): NetId {
        val world = worldOf(at(atX, atY))
        val entity = host.world.entity {
            it += PhysicsBody(x = world.x, y = world.y, angle = angle)
            if (box != null) it += box
            if (circle != null) it += circle
        }
        val id = host.ctx[CoreModule.NET_IDS].allocate(entity)
        if (select) loop.select(listOf(id))
        return id
    }

    private fun bodyOf(id: NetId): PhysicsBody = with(host.world) { checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(id)) { "$id is gone" }[PhysicsBody] }

    private fun boxOf(id: NetId): Box = with(host.world) { checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(id)) { "$id is gone" }[Box] }

    private fun circleOf(id: NetId): Circle = with(host.world) { checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(id)) { "$id is gone" }[Circle] }

    private fun open(block: (UiTest) -> Unit) {
        uiTest { session.window.content() }.use { ui ->
            ui.settle()
            frames(ui)
            block(ui)
        }
    }

    private fun drag(ui: UiTest, from: ViewPoint, to: ViewPoint) {
        assertTrue(ui.press(screenOf(ui, from)), "the Scene tab took no press")
        // In steps, as a hand drags.
        for (step in 1..DRAG_STEPS) {
            val t = step.toFloat() / DRAG_STEPS
            ui.dragTo(screenOf(ui, ViewPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)))
        }
        ui.release()
        frames(ui)
    }

    /** Sends, runs and delivers what the window asked for. */
    private fun frames(ui: UiTest) {
        repeat(FRAMES) {
            session.frame()
            loop.pump()
            ui.settle()
        }
    }

    /** View point ([dx], [dy]) view pixels from the middle of the view. */
    private fun at(dx: Float, dy: Float): ViewPoint = ViewPoint(VIEW_WIDTH / 2f + dx, VIEW_HEIGHT / 2f + dy)

    private fun viewOf(x: Float, y: Float): ViewPoint = ViewPoint().also { camera.project(x, y, 0f, it) }

    private fun worldOf(view: ViewPoint): ViewPoint = ViewPoint().also { camera.unproject(view.x, view.y, it) }

    /** The world x drawn [dx] view pixels right of the middle of the view. */
    private fun worldX(dx: Float): Float = worldOf(at(dx, 0f)).x

    private fun worldY(dy: Float): Float = worldOf(at(0f, dy)).y

    /** World units that cover [pixels] view pixels. */
    private fun units(pixels: Float): Float = pixels / camera.projection.scaleX

    private fun assertNear(expected: Float, actual: Float, message: String = "") {
        assertTrue(abs(expected - actual) < TOLERANCE, "$message: expected $expected, was $actual")
    }

    private fun assertOnStep(value: Float, step: Float, message: String) {
        val steps = value / step
        assertTrue(abs(steps - kotlin.math.round(steps)) < TOLERANCE, "$message: $value is $steps steps of $step")
    }

    private companion object {
        const val VIEW_WIDTH = 640
        const val VIEW_HEIGHT = 360

        /** Where on an arrow a person grabs it: along its shaft, clear of the square in the middle. */
        const val ARROW_GRIP = 40f

        const val COMMIT = "editor.commit_edit"

        const val DRAG_STEPS = 4

        /** Enough for a drag's begin, update and commit to be sent, run and answered. */
        const val FRAMES = 6

        const val TOLERANCE = 1e-3f
    }
}
