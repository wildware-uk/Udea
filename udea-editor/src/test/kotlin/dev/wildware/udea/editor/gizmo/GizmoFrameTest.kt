package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.identity.NetId
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What issue #236 adds to the public gizmo API, as values: the axes a handle is aligned to, the
 * drawings a gizmo declares that nobody grabs, and the snapping a write asks for.
 */
class GizmoFrameTest {

    private class Dial(var turn: Float = 0f) : Component<Dial> {
        override fun type(): ComponentType<Dial> = Dial

        companion object : ComponentType<Dial>()
    }

    private val entity = NetId.of(index = 4, generation = 0)

    @Test
    fun `the world frame is the world's axes, and a heading turns X and Y about Z and leaves Z alone`() {
        assertEquals(WorldPoint(1f, 0f, 0f), AxisFrame.WORLD.direction(Axis.X))
        assertEquals(WorldPoint(0f, 1f, 0f), AxisFrame.WORLD.direction(Axis.Y))
        assertEquals(WorldPoint(0f, 0f, 1f), AxisFrame.WORLD.direction(Axis.Z))

        // A quarter turn anticlockwise: local X is world +Y, local Y is world -X.
        val quarter = AxisFrame.heading((PI / 2).toFloat())
        assertNear(WorldPoint(0f, 1f, 0f), quarter.direction(Axis.X))
        assertNear(WorldPoint(-1f, 0f, 0f), quarter.direction(Axis.Y))
        assertNear(WorldPoint(0f, 0f, 1f), quarter.direction(Axis.Z))
        assertEquals(AxisFrame.WORLD, AxisFrame.heading(0f))
    }

    @Test
    fun `a drag measures its movement along a direction, and spreads a box along a frame's axes`() {
        val eighth = AxisFrame.heading((PI / 4).toFloat())
        val diagonal = eighth.direction(Axis.X)
        // Three right and three up is 3 * sqrt(2) along the diagonal, and nothing across it.
        val drag = Drag(start = WorldPoint(0f, 0f), at = WorldPoint(3f, 3f))
        assertEquals(3f * sqrt(2f), drag.along(diagonal), 1e-5f)
        assertEquals(0f, drag.along(eighth.direction(Axis.Y)), 1e-5f)

        // A corner 1 out along the turned X, dragged 2 further along it: the box is 4 wider in its
        // own frame and no taller, where the world frame would have it 2.83 wider and 2.83 taller.
        val centre = WorldPoint(10f, 10f)
        val corner = WorldPoint(10f + diagonal.x, 10f + diagonal.y)
        val further = Drag(corner, WorldPoint(corner.x + 2f * diagonal.x, corner.y + 2f * diagonal.y))
        assertEquals(4f, further.spread(Axis.X, centre, eighth), 1e-5f)
        assertEquals(0f, further.spread(Axis.Y, centre, eighth), 1e-5f)
        assertEquals(2f * 2f * diagonal.x, further.spread(Axis.X, centre), 1e-5f, "the world frame is the default")
    }

    @Test
    fun `a handle carries the axes it was declared in, and the world's when it names none`() {
        val quarter = AxisFrame.heading((PI / 2).toFloat())
        val gizmo = object : Gizmo<Dial> {
            override val component: ComponentType<Dial> = Dial

            override fun GizmoScope<Dial>.build(target: GizmoTarget<Dial>) {
                handle(target.origin, HandleShape.Arrow(Axis.X), DragConstraint.Along(Axis.X), target.axes) {}
                handle(target.origin, HandleShape.Point, DragConstraint.Across(Plane.XY)) {}
            }
        }

        val (turned, plain) = gizmo.handles(GizmoTarget(entity, Dial(), WorldPoint(0f, 0f), axes = quarter))

        assertEquals(quarter, turned.axes)
        assertEquals(AxisFrame.WORLD, plain.axes)
        assertEquals(AxisFrame.WORLD, GizmoTarget(entity, Dial(), WorldPoint(0f, 0f)).axes, "a target's axes default to the world's")
    }

    @Test
    fun `a gizmo declares marks beside its handles, in order, and neither list sees the other`() {
        val gizmo = object : Gizmo<Dial> {
            override val component: ComponentType<Dial> = Dial

            override fun GizmoScope<Dial>.build(target: GizmoTarget<Dial>) {
                mark(target.origin, HandleShape.Circle(5f))
                handle(target.origin, HandleShape.Point, DragConstraint.Across(Plane.XY)) {}
                mark(target.origin, HandleShape.Line(WorldPoint(5f, 0f)))
            }
        }
        val target = GizmoTarget(entity, Dial(), WorldPoint(0f, 0f))

        assertEquals(
            listOf(
                Mark(WorldPoint(0f, 0f), HandleShape.Circle(5f, Axis.Z)),
                Mark(WorldPoint(0f, 0f), HandleShape.Line(WorldPoint(5f, 0f))),
            ),
            gizmo.marks(target),
        )
        assertEquals(1, gizmo.handles(target).size)
    }

    @Test
    fun `a write says how it snaps, and says nothing when it is not told`() {
        val gizmo = object : Gizmo<Dial> {
            override val component: ComponentType<Dial> = Dial

            override fun GizmoScope<Dial>.build(target: GizmoTarget<Dial>) {
                handle(target.origin, HandleShape.Ring(Axis.Z), DragConstraint.Across(Plane.XY)) { drag ->
                    write(Dial::turn, drag.dx, Snap.Angle)
                }
                handle(target.origin, HandleShape.Point, DragConstraint.Across(Plane.XY)) { drag ->
                    write(Dial::turn, drag.dx)
                }
            }
        }
        val (angled, plain) = gizmo.handles(GizmoTarget(entity, Dial(), WorldPoint(0f, 0f)))
        val drag = Drag(WorldPoint(0f, 0f), WorldPoint(0.5f, 0f))

        assertEquals(listOf(FieldWrite(entity, Dial, FieldName("turn"), 0.5f, Snap.Angle)), angled.drag(drag))
        assertEquals(Snap.None, plain.drag(drag).single().snap)
    }

    private fun assertNear(expected: WorldPoint, actual: WorldPoint) {
        assertEquals(expected.x, actual.x, 1e-6f, "x of $actual")
        assertEquals(expected.y, actual.y, 1e-6f, "y of $actual")
        assertEquals(expected.z, actual.z, 1e-6f, "z of $actual")
    }
}
