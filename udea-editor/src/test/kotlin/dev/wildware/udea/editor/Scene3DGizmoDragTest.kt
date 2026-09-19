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
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.editor.gizmo.HandlePainter
import dev.wildware.udea.editor.gizmo.WorldPoint
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewDimension
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.WorldViewport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dragging the built-in 3D gizmos through the editor window's own pointer, with the Scene tab's 3D
 * camera on and no GL (issue #237).
 *
 * The pointer goes in through the window as a person's does. Every drag is aimed by world points
 * ([GizmoAim]): it starts at the pixel a point on the handle is drawn at and ends at the pixel of a
 * point the handle can reach, so the expected change is worked out in the world - how far between
 * the two points, what angle between them - and never from the pixels. What is asserted is the
 * `Transform3D` an agent reads, and the `editor` author's history as `editor.history` lists it: each
 * drag one undo entry.
 *
 * The camera looks from an angle no axis lines up with, so no handle is dragged face-on and the rings
 * are drawn as ellipses crossing the arrows, as a person sees them. The gizmos are the fixture's,
 * calling the built-ins through the public API alone ([TransformGizmos]). `GlGizmo3DDragTest` does the
 * same through a real Kool backend, the real mouse, and the Fox.
 */
class Scene3DGizmoDragTest {

    private val host = GameHost(RenderMode.Headless, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()))

    private val loop = EditorToolLoop(host, fixtureComponents())

    /** The 3D camera, round the world's origin from an angle, fitted up front so handles can be found in view pixels. */
    private val camera = EditorCamera().also {
        it.dimension = ViewDimension.ThreeD
        it.yawDegrees = YAW
        it.pitchDegrees = PITCH
        it.distance = DISTANCE
        it.fit(VIEW_WIDTH, VIEW_HEIGHT)
    }

    private val aim = GizmoAim(::viewOf)

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
        gizmos = EditorGizmos(TransformGizmos, host.world, host.ctx[CoreModule.NET_IDS], loop.components, TransformPlacement, preferences),
    )

    // --- translate --------------------------------------------------------------------------------

    @Test
    fun `each arrow moves the model along its own axis alone, by the drag, as one undo entry`() {
        val model = model(Transform3D(x = 0.5f, y = -0.25f, z = 0.2f))
        open { ui ->
            var edits = 0
            for (axis in listOf(X, Y, Z)) {
                val before = transformOf(model).position()
                // Grabbed on the shaft, and dragged out along it.
                val grip = aim.pixelsOut(before, axis, ARROW_GRIP)
                drag(ui, viewOf(before.plus(axis, grip)), viewOf(before.plus(axis, grip + 0.6f)))

                val moved = transformOf(model).position().minus(before)
                assertNear(0.6f, dot(moved, axis), "the arrow along $axis did not move the model by the drag")
                assertNear(0f, moved.minus(axis.times(dot(moved, axis))).length(), "the arrow along $axis moved the model off its line")
                assertEquals(List(++edits) { COMMIT }, loop.history(), "a drag is one undo entry")
            }
        }
    }

    @Test
    fun `each plane square moves the model within its plane alone`() {
        val model = model(Transform3D())
        open { ui ->
            var edits = 0
            for ((u, v, normal) in listOf(Triple(X, Y, Z), Triple(Y, Z, X), Triple(X, Z, Y))) {
                val before = transformOf(model).position()
                val from = aim.planeTab(before, u, v)
                drag(ui, viewOf(from), viewOf(from.plus(u, 0.4f).plus(v, -0.7f)))

                val moved = transformOf(model).position().minus(before)
                assertNear(0.4f, dot(moved, u), "the $u-$v square did not move the model along $u")
                assertNear(-0.7f, dot(moved, v), "the $u-$v square did not move the model along $v")
                assertNear(0f, dot(moved, normal), "the $u-$v square moved the model off its plane")
                assertEquals(List(++edits) { COMMIT }, loop.history())
            }
        }
    }

    // --- rotate -----------------------------------------------------------------------------------

    @Test
    fun `each ring turns its own angle alone by a quarter for a quarter round it`() {
        open { ui ->
            for (index in 0 until 3) {
                // A model of its own for each ring: two quarter turns in a row would stand the X ring
                // on the Z ring's axis, the gimbal's one blind spot, and leave no part of either to take.
                val model = model(Transform3D(rotationX = 0.1f, rotationY = -0.2f, rotationZ = 0.3f))
                frames(ui)
                val t = transformOf(model)
                val before = t.angles()
                val rings = aim.rings(t)
                val from = aim.ringGrip(rings, index)
                // A quarter of a turn round the ring from where it is taken, right-handed.
                drag(ui, viewOf(rings[index].at(from)), viewOf(rings[index].at(from + 0.25f)))

                val after = transformOf(model).angles()
                for (angle in after.indices) {
                    val expected = before[angle] + if (angle == index) (PI / 2).toFloat() else 0f
                    assertNear(expected, after[angle], "ring $index left angle $angle at ${after[angle]}")
                }
                assertEquals(List(index + 1) { COMMIT }, loop.history())
            }
        }
    }

    // --- scale ------------------------------------------------------------------------------------

    @Test
    fun `an axis box scales its own axis by how much further out it is pulled`() {
        val model = model(Transform3D(scaleX = 2f, scaleY = 1f, scaleZ = 0.5f))
        open { ui ->
            // The X box stands off along X: pulled half as far out again, X is half as big again.
            val centre = transformOf(model).position()
            val reach = aim.pixelsOut(centre, X, HandlePainter.SCALE_REACH)
            drag(ui, viewOf(centre.plus(X, reach)), viewOf(centre.plus(X, reach * 1.5f)))
            assertEquals(listOf(3f, 1f, 0.5f), transformOf(model).scales().map { round(it * 1000f) / 1000f }, "the X box scaled the wrong axis or amount")
            assertEquals(listOf(COMMIT), loop.history())
        }
    }

    @Test
    fun `the middle box doubles every axis for a drag up and right as long as the axis boxes stand off`() {
        // Face-on to the XZ plane, so a point up and right of the model is in the plane the drag is held to.
        camera.yawDegrees = -90f
        camera.pitchDegrees = 0f
        val model = model(Transform3D(scaleX = 2f, scaleY = 1f, scaleZ = 0.5f))
        open { ui ->
            val centre = transformOf(model).position()
            val reach = camera.unitsPerPixelAt(centre.x, centre.y, centre.z) * HandlePainter.SCALE_REACH
            drag(ui, viewOf(centre), viewOf(centre.plus(X, reach / sqrt(2f)).plus(Z, reach / sqrt(2f))))
            assertEquals(listOf(4f, 2f, 1f), transformOf(model).scales().map { round(it * 1000f) / 1000f })
            assertEquals(listOf(COMMIT), loop.history())
        }
    }

    // --- Escape, snapping, axes ---------------------------------------------------------------------

    @Test
    fun `Escape mid-drag puts the model back and files nothing`() {
        val model = model(Transform3D(x = 1f, y = 2f, z = 0.5f))
        open { ui ->
            val before = transformOf(model).position()
            val grip = aim.pixelsOut(before, Z, ARROW_GRIP)
            assertTrue(ui.press(screenOf(ui, viewOf(before.plus(Z, grip)))), "the Scene tab took no press")
            ui.dragTo(screenOf(ui, viewOf(before.plus(Z, grip + 1f))))
            frames(ui)
            assertNear(before.z + 1f, transformOf(model).z, "the drag was not live before Escape")

            ui.keyDown(Key.Escape)
            ui.keyUp(Key.Escape)
            frames(ui)
            ui.release()
            frames(ui)
            assertEquals(before, transformOf(model).position(), "Escape did not put the model back")
            assertEquals(emptyList(), loop.history(), "a cancelled drag filed an undo entry")
        }
    }

    @Test
    fun `grid snapping rounds a 3D move to the step, and Ctrl held writes it exactly`() {
        val model = model(Transform3D(z = 0f))
        preferences.gridSnap = true
        preferences.gridStep = 0.5f
        open { ui ->
            val grip = aim.pixelsOut(ORIGIN, Z, ARROW_GRIP)
            drag(ui, viewOf(Z.times(grip)), viewOf(Z.times(grip + 0.7f)))
            assertNear(0.5f, transformOf(model).z, "the move was not rounded to the nearest half")

            ui.keyDown(Key.Control, Modifiers(Modifiers.CONTROL))
            val from = transformOf(model).position()
            val again = aim.pixelsOut(from, Z, ARROW_GRIP)
            drag(ui, viewOf(from.plus(Z, again)), viewOf(from.plus(Z, again + 0.7f)))
            ui.keyUp(Key.Control)
            assertNear(1.2f, transformOf(model).z, "Ctrl did not turn snapping off")
        }
    }

    @Test
    fun `angle snapping rounds a ring's turn to the step`() {
        val model = model(Transform3D())
        preferences.angleSnap = true
        preferences.angleStepDegrees = 15f
        open { ui ->
            val rings = aim.rings(transformOf(model))
            val from = aim.ringGrip(rings, Z_RING)
            // Fifty degrees round the Z ring: the nearest fifteen is forty-five.
            drag(ui, viewOf(rings[Z_RING].at(from)), viewOf(rings[Z_RING].at(from + 50f / 360f)))
            assertNear((PI / 4).toFloat(), transformOf(model).rotationZ, "the turn was not snapped to 15 degrees")
        }
    }

    @Test
    fun `local axes turn the arrows with the model`() {
        // Turned a quarter about Z: its own X is the world's Y.
        val model = model(Transform3D(rotationZ = (PI / 2).toFloat()))
        preferences.axes = GizmoAxes.Local
        open { ui ->
            val grip = aim.pixelsOut(ORIGIN, Y, ARROW_GRIP)
            drag(ui, viewOf(Y.times(grip)), viewOf(Y.times(grip + 0.8f)))
            assertEquals(0f, transformOf(model).x, "the local X arrow moved the model along the world's X")
            assertNear(0.8f, transformOf(model).y, "the local X arrow did not move the model along its own X")
            assertEquals(listOf(COMMIT), loop.history())
        }
    }

    // --- fixture ----------------------------------------------------------------------------------

    private fun model(transform: Transform3D): NetId {
        val entity = host.world.entity { it += transform }
        val id = host.ctx[CoreModule.NET_IDS].allocate(entity)
        loop.select(listOf(id))
        return id
    }

    private fun transformOf(id: NetId): Transform3D =
        with(host.world) { checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(id)) { "$id is gone" }[Transform3D] }

    private fun Transform3D.position(): WorldPoint = WorldPoint(x, y, z)

    private fun Transform3D.angles(): List<Float> = listOf(rotationX, rotationY, rotationZ)

    private fun Transform3D.scales(): List<Float> = listOf(scaleX, scaleY, scaleZ)

    private fun open(block: (UiTest) -> Unit) {
        uiTest { session.window.content() }.use { ui ->
            ui.settle()
            frames(ui)
            block(ui)
        }
    }

    private fun drag(ui: UiTest, from: ViewPoint, to: ViewPoint) {
        assertTrue(ui.press(screenOf(ui, from)), "the Scene tab took no press")
        for (step in 1..DRAG_STEPS) {
            val t = step.toFloat() / DRAG_STEPS
            ui.dragTo(screenOf(ui, ViewPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)))
        }
        ui.release()
        frames(ui)
    }

    private fun frames(ui: UiTest) {
        repeat(FRAMES) {
            session.frame()
            loop.pump()
            ui.settle()
        }
    }

    private fun viewOf(point: WorldPoint): ViewPoint =
        ViewPoint().also { check(camera.project(point.x, point.y, point.z, it)) { "$point is behind the camera" } }

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < TOLERANCE, "$message: expected $expected, was $actual")
    }

    private companion object {
        const val VIEW_WIDTH = 640
        const val VIEW_HEIGHT = 360

        /** Round the origin from ahead and to the left, a little above: no axis lies along the line of sight. */
        const val YAW = -60f
        const val PITCH = 25f
        const val DISTANCE = 8f

        val ORIGIN = WorldPoint(0f, 0f, 0f)
        val X = WorldPoint(1f, 0f, 0f)
        val Y = WorldPoint(0f, 1f, 0f)
        val Z = WorldPoint(0f, 0f, 1f)

        /** The Z ring's place among the rings the gizmo declares: X's, Y's, Z's. */
        const val Z_RING = 2

        /** Where on an arrow a person grabs it, in view pixels out from the model: along its shaft. */
        const val ARROW_GRIP = 40f

        const val COMMIT = "editor.commit_edit"

        const val DRAG_STEPS = 4

        /** Enough for a drag's begin, update and commit to be sent, run and answered. */
        const val FRAMES = 6

        /** World units: well under a pixel at this camera's distance. */
        const val TOLERANCE = 2e-3f
    }
}
