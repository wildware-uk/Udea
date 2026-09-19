package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.identity.NetId
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built-in 2D gizmos (issue #236) as values: which handles each declares, where, and what a drag
 * on each one writes - against numbers worked out here, not read off the code.
 *
 * Each built-in is a public function on [GizmoScope], so a hand-written gizmo reaches the same one a
 * handle annotation generates a call to: there is no private path. [Crate] is a component with a
 * field for every built-in, and each gizmo below is a hand-written one calling one of them.
 */
class BuiltinGizmosTest {

    private class Crate(
        var x: Float = 0f,
        var y: Float = 0f,
        var heading: Float = 0f,
        var width: Float = 1f,
        var height: Float = 1f,
        var reach: Float = 1f,
        var sight: Float = 1f,
    ) : Component<Crate> {
        override fun type(): ComponentType<Crate> = Crate

        companion object : ComponentType<Crate>()
    }

    private object Move : Gizmo<Crate> {
        override val component: ComponentType<Crate> = Crate

        override fun GizmoScope<Crate>.build(target: GizmoTarget<Crate>) {
            moveHandles(target, Crate::x, target.component.x, Crate::y, target.component.y)
        }
    }

    private object Size : Gizmo<Crate> {
        override val component: ComponentType<Crate> = Crate

        override fun GizmoScope<Crate>.build(target: GizmoTarget<Crate>) {
            sizeHandles(target, Crate::width, target.component.width, Crate::height, target.component.height)
        }
    }

    private object Turn : Gizmo<Crate> {
        override val component: ComponentType<Crate> = Crate

        override fun GizmoScope<Crate>.build(target: GizmoTarget<Crate>) {
            rotationHandle(target, Crate::heading, target.component.heading)
        }
    }

    private object Reach : Gizmo<Crate> {
        override val component: ComponentType<Crate> = Crate

        override fun GizmoScope<Crate>.build(target: GizmoTarget<Crate>) {
            radiusHandle(target, Crate::reach, target.component.reach)
            rangeHandle(target, Crate::sight, target.component.sight)
        }
    }

    private val entity = NetId.of(index = 9, generation = 1)

    private fun <C : Component<C>> Handle<C>.writes(from: WorldPoint, to: WorldPoint): Map<String, Float> =
        drag(Drag(from, to)).associate { it.field.value to it.value }

    private fun offset(point: WorldPoint, dx: Float, dy: Float): WorldPoint = WorldPoint(point.x + dx, point.y + dy, point.z)

    // --- move -----------------------------------------------------------------------------------

    @Test
    fun `move is two axis arrows and a free square, all on the entity, each moving it by the drag`() {
        val crate = Crate(x = 4f, y = -2f)
        val handles = Move.handles(GizmoTarget(entity, crate, WorldPoint(4f, -2f)))

        assertEquals(
            listOf(HandleShape.Arrow(Axis.X), HandleShape.Arrow(Axis.Y), HandleShape.PlaneSquare(Plane.XY)),
            handles.map { it.shape },
        )
        assertEquals(
            listOf(DragConstraint.Along(Axis.X), DragConstraint.Along(Axis.Y), DragConstraint.Across(Plane.XY)),
            handles.map { it.constraint },
        )
        assertTrue(handles.all { it.at == WorldPoint(4f, -2f) }, "a move handle is not on the entity: ${handles.map { it.at }}")
        // Grabbed off-centre and dragged 3 right, 5 up: the entity moves by exactly that, and an arrow
        // writes only its own axis, so snapping it never moves the other one.
        val (alongX, alongY, free) = handles
        val grabbed = offset(alongX.at, 0.4f, -0.3f)
        assertEquals(mapOf("x" to 7f), alongX.writes(grabbed, offset(grabbed, 3f, 5f)))
        assertEquals(mapOf("y" to 3f), alongY.writes(grabbed, offset(grabbed, 3f, 5f)))
        assertEquals(mapOf("x" to 7f, "y" to 3f), free.writes(grabbed, offset(grabbed, 3f, 5f)))
        assertTrue(handles.flatMap { it.drag(Drag(it.at, it.at)) }.all { it.snap == Snap.Grid }, "a position snaps to the grid")
    }

    @Test
    fun `move's handles are aligned to the target's axes`() {
        val quarter = AxisFrame.heading((PI / 2).toFloat())
        val handles = Move.handles(GizmoTarget(entity, Crate(), WorldPoint(0f, 0f), axes = quarter))

        assertTrue(handles.all { it.axes == quarter }, "a move handle ignored the target's axes")
        // Turned an eighth, each arrow runs across both world axes, so each writes both fields.
        val eighth = Move.handles(GizmoTarget(entity, Crate(), WorldPoint(0f, 0f), axes = AxisFrame.heading((PI / 4).toFloat())))
        assertTrue(eighth.all { handle -> handle.drag(Drag(handle.at, handle.at)).map { it.field.value } == listOf("x", "y") })
    }

    // --- size -----------------------------------------------------------------------------------

    @Test
    fun `size is four corners and four edges round the box, and an outline between the corners`() {
        val crate = Crate(width = 4f, height = 2f)
        val target = GizmoTarget(entity, crate, WorldPoint(10f, 10f))
        val handles = Size.handles(target)

        val corners = handles.filter { it.shape == HandleShape.BoxCorner }.map { it.at }.toSet()
        assertEquals(setOf(WorldPoint(12f, 11f), WorldPoint(8f, 11f), WorldPoint(8f, 9f), WorldPoint(12f, 9f)), corners)
        val edges = handles.filter { it.shape is HandleShape.BoxEdge }.associate { it.at to it.shape }
        assertEquals(
            mapOf(
                WorldPoint(12f, 10f) to HandleShape.BoxEdge(Axis.X),
                WorldPoint(8f, 10f) to HandleShape.BoxEdge(Axis.X),
                WorldPoint(10f, 11f) to HandleShape.BoxEdge(Axis.Y),
                WorldPoint(10f, 9f) to HandleShape.BoxEdge(Axis.Y),
            ),
            edges,
        )
        assertEquals(8, handles.size)
        assertEquals(
            setOf(
                Mark(WorldPoint(12f, 11f), HandleShape.Line(WorldPoint(8f, 11f))),
                Mark(WorldPoint(8f, 11f), HandleShape.Line(WorldPoint(8f, 9f))),
                Mark(WorldPoint(8f, 9f), HandleShape.Line(WorldPoint(12f, 9f))),
                Mark(WorldPoint(12f, 9f), HandleShape.Line(WorldPoint(12f, 11f))),
            ),
            Size.marks(target).toSet(),
        )
    }

    @Test
    fun `dragging any corner outwards grows the box about its centre, and an edge grows only its own side`() {
        val crate = Crate(width = 4f, height = 2f)
        val handles = Size.handles(GizmoTarget(entity, crate, WorldPoint(10f, 10f)))
        val bottomLeft = handles.single { it.at == WorldPoint(8f, 9f) }
        val right = handles.single { it.at == WorldPoint(12f, 10f) }
        val top = handles.single { it.at == WorldPoint(10f, 11f) }

        // The bottom-left corner 1 further left and 1 further down: both faces move, so 2 wider and 2 taller.
        assertEquals(mapOf("width" to 6f, "height" to 4f), bottomLeft.writes(bottomLeft.at, offset(bottomLeft.at, -1f, -1f)))
        assertEquals(mapOf("width" to 5f), right.writes(right.at, offset(right.at, 0.5f, 0f)))
        // Grabbed half a unit beyond the top and dragged onto the centre: 2 less 3 is clamped to nothing.
        assertEquals(mapOf("height" to 0f), top.writes(offset(top.at, 0f, 0.5f), WorldPoint(10f, 10f)), "a size is never negative")
        assertEquals(DragConstraint.Along(Axis.X), right.constraint)
        assertEquals(DragConstraint.Along(Axis.Y), top.constraint)
        assertTrue(bottomLeft.drag(Drag(bottomLeft.at, bottomLeft.at)).all { it.snap == Snap.Grid }, "a size snaps to the grid")
    }

    @Test
    fun `a box in local axes is turned with the entity, and grows along its own sides`() {
        val quarter = AxisFrame.heading((PI / 2).toFloat())
        val handles = Size.handles(GizmoTarget(entity, Crate(width = 4f, height = 2f), WorldPoint(0f, 0f), axes = quarter))
        // Turned a quarter, the box's own +X is world +Y: its right edge is 2 up.
        val right = handles.single { it.shape == HandleShape.BoxEdge(Axis.X) && it.at.y > 1f }

        assertEquals(0f, right.at.x, 1e-5f)
        assertEquals(2f, right.at.y, 1e-5f)
        // Dragged 1 further up - along its own X - it is 2 wider.
        assertEquals(6f, right.writes(right.at, offset(right.at, 0f, 1f)).getValue("width"), 1e-5f)
    }

    // --- rotation -------------------------------------------------------------------------------

    @Test
    fun `rotation is one ring about the up axis on the entity, adding the turn and snapping to the angle`() {
        val crate = Crate(heading = 0.5f)
        val handle = Turn.handles(GizmoTarget(entity, crate, WorldPoint(3f, 3f))).single()

        assertEquals(WorldPoint(3f, 3f), handle.at)
        assertEquals(HandleShape.Ring(Axis.Z), handle.shape)
        assertEquals(DragConstraint.Across(Plane.XY), handle.constraint)
        // From east of the entity to north of it: a quarter turn anticlockwise.
        val written = handle.drag(Drag(WorldPoint(5f, 3f), WorldPoint(3f, 5f))).single()
        assertEquals(0.5f + (PI / 2).toFloat(), written.value, 1e-6f)
        assertEquals(Snap.Angle, written.snap)
    }

    // --- radius and range -----------------------------------------------------------------------

    @Test
    fun `a radius and a range each have a grip on the rim and a circle marked, and follow the distance`() {
        val crate = Crate(reach = 2f, sight = 5f)
        val target = GizmoTarget(entity, crate, WorldPoint(1f, 1f))
        val (reach, sight) = Reach.handles(target)

        assertEquals(WorldPoint(3f, 1f), reach.at)
        assertEquals(WorldPoint(6f, 1f), sight.at)
        assertEquals(HandleShape.Point, reach.shape)
        assertEquals(HandleShape.Line(WorldPoint(1f, 1f)), sight.shape, "a range draws its spoke back to the entity")
        assertEquals(DragConstraint.Along(Axis.X), sight.constraint)
        assertEquals(
            listOf(Mark(WorldPoint(1f, 1f), HandleShape.Circle(2f)), Mark(WorldPoint(1f, 1f), HandleShape.Circle(5f))),
            Reach.marks(target),
        )
        assertEquals(mapOf("sight" to 8f), sight.writes(sight.at, offset(sight.at, 3f, 0f)))
        // Grabbed 4 out and dragged onto the entity: 2 less 4 is clamped to nothing.
        assertEquals(mapOf("reach" to 0f), reach.writes(WorldPoint(5f, 1f), WorldPoint(1f, 1f)))
        assertEquals(Snap.Grid, reach.drag(Drag(reach.at, reach.at)).single().snap)
    }

    @Test
    fun `a range's grip is on the rim along the target's own X`() {
        val quarter = AxisFrame.heading((PI / 2).toFloat())
        val sight = Reach.handles(GizmoTarget(entity, Crate(sight = 5f), WorldPoint(0f, 0f), axes = quarter))[1]

        assertEquals(0f, sight.at.x, 1e-5f)
        assertEquals(5f, sight.at.y, 1e-5f)
        assertEquals(quarter, sight.axes)
    }
}
