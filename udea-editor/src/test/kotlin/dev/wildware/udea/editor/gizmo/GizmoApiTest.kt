package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The public gizmo API as values, with no window (issue #233): what a gizmo declares, and what a
 * drag on one of its handles turns into.
 *
 * The property every test here leans on is the spec's first rule: **a gizmo never mutates the
 * world.** A drag answers field writes, and the component the gizmo read is untouched after it.
 */
class GizmoApiTest {

    /** A component with one float, and one that is not a float. */
    private class Disc(var radius: Float, var label: Int = 0) : Component<Disc> {
        override fun type(): ComponentType<Disc> = Disc

        companion object : ComponentType<Disc>()
    }

    /** One ring handle at the rim, dragged along X, writing the distance from the centre. */
    private object DiscGizmo : Gizmo<Disc> {
        override val component: ComponentType<Disc> = Disc

        override fun GizmoScope<Disc>.build(target: GizmoTarget<Disc>) {
            val centre = target.origin
            handle(
                at = WorldPoint(centre.x + target.component.radius, centre.y, centre.z),
                shape = HandleShape.Point,
                constraint = DragConstraint.Along(Axis.X),
            ) { drag ->
                write(Disc::radius, drag.at.distanceTo(centre))
            }
        }
    }

    private val entity = NetId.of(index = 7, generation = 2)

    @Test
    fun `a gizmo declares its handles as values, where the component says they are`() {
        val disc = Disc(radius = 3f)
        val handles = DiscGizmo.handles(GizmoTarget(entity, disc, origin = WorldPoint(10f, 20f)))

        val handle = handles.single()
        assertEquals(WorldPoint(13f, 20f, 0f), handle.at)
        assertEquals(HandleShape.Point, handle.shape)
        assertEquals(DragConstraint.Along(Axis.X), handle.constraint)
    }

    @Test
    fun `a drag answers field writes and leaves the component alone`() {
        val disc = Disc(radius = 3f)
        val handle = DiscGizmo.handles(GizmoTarget(entity, disc, origin = WorldPoint(10f, 20f))).single()

        val writes = handle.drag(Drag(start = handle.at, at = WorldPoint(15f, 20f)))

        assertEquals(listOf(FieldWrite(entity, Disc, FieldName("radius"), 5f)), writes)
        assertEquals(3f, disc.radius, "a drag mutated the component; it must only answer writes")
    }

    @Test
    fun `every drag is answered afresh, from the values the handle was declared with`() {
        val disc = Disc(radius = 3f)
        val handle = DiscGizmo.handles(GizmoTarget(entity, disc, origin = WorldPoint(0f, 0f))).single()

        assertEquals(5f, handle.drag(Drag(handle.at, WorldPoint(5f, 0f))).single().value)
        assertEquals(1f, handle.drag(Drag(handle.at, WorldPoint(1f, 0f))).single().value)
    }

    @Test
    fun `a drag response that writes one field twice is refused rather than resolved`() {
        val twice = object : Gizmo<Disc> {
            override val component: ComponentType<Disc> = Disc

            override fun GizmoScope<Disc>.build(target: GizmoTarget<Disc>) {
                handle(target.origin, HandleShape.Sphere, DragConstraint.ViewPlane) { drag ->
                    write(Disc::radius, drag.at.x)
                    write(Disc::radius, drag.at.y)
                }
            }
        }
        val handle = twice.handles(GizmoTarget(entity, Disc(1f), WorldPoint(0f, 0f))).single()

        val refused = assertFailsWith<IllegalArgumentException> { handle.drag(Drag(handle.at, WorldPoint(1f, 2f))) }
        assertTrue("radius" in refused.message.orEmpty(), "the refusal must name the field: ${refused.message}")
    }

    @Test
    fun `a drag response that writes a value that is not a number is refused`() {
        val broken = object : Gizmo<Disc> {
            override val component: ComponentType<Disc> = Disc

            override fun GizmoScope<Disc>.build(target: GizmoTarget<Disc>) {
                handle(target.origin, HandleShape.Sphere, DragConstraint.ViewPlane) { drag ->
                    write(Disc::radius, drag.at.x / 0f * 0f)
                }
            }
        }
        val handle = broken.handles(GizmoTarget(entity, Disc(1f), WorldPoint(0f, 0f))).single()

        assertFailsWith<IllegalArgumentException> { handle.drag(Drag(handle.at, WorldPoint(1f, 2f))) }
    }

    @Test
    fun `handles are declared in order and a gizmo with none declares none`() {
        val two = object : Gizmo<Disc> {
            override val component: ComponentType<Disc> = Disc

            override fun GizmoScope<Disc>.build(target: GizmoTarget<Disc>) {
                handle(WorldPoint(1f, 0f), HandleShape.BoxCorner, DragConstraint.Across(Plane.XY)) {}
                handle(WorldPoint(2f, 0f), HandleShape.Ring(Axis.Z), DragConstraint.Across(Plane.XY)) {}
            }
        }
        val none = object : Gizmo<Disc> {
            override val component: ComponentType<Disc> = Disc

            override fun GizmoScope<Disc>.build(target: GizmoTarget<Disc>) = Unit
        }
        val target = GizmoTarget(entity, Disc(1f), WorldPoint(0f, 0f))

        assertEquals(listOf(WorldPoint(1f, 0f), WorldPoint(2f, 0f)), two.handles(target).map(Handle<Disc>::at))
        assertEquals(emptyList(), none.handles(target))
    }

    @Test
    fun `a drag measures how far it moved, stretched from a centre, spread about it and turned about it`() {
        val centre = WorldPoint(10f, 10f)
        // Grabbed 2 right of the centre, dragged to 4 right and 3 up of it.
        val drag = Drag(start = WorldPoint(12f, 10f), at = WorldPoint(14f, 13f))

        assertEquals(2f, drag.dx)
        assertEquals(3f, drag.dy)
        assertEquals(0f, drag.dz)
        // From 2 away to 5 away.
        assertEquals(3f, drag.stretchFrom(centre))
        // A corner at 2 right moved to 4 right widens a centred box by twice that: 4.
        assertEquals(4f, drag.spread(Axis.X, centre))
        assertEquals(6f, drag.spread(Axis.Y, centre))
        // From pointing along +X to pointing at (4, 3): atan2(3, 4).
        assertEquals(kotlin.math.atan2(3f, 4f), drag.turnAbout(centre), 1e-6f)
    }

    @Test
    fun `a turn is measured the short way round, so crossing the back of the ring does not jump a whole turn`() {
        val centre = WorldPoint(0f, 0f)
        // Just above -X to just below it: a small anticlockwise step across the atan2 seam, where
        // atan2 jumps by -2pi.
        val drag = Drag(start = WorldPoint(-1f, 0.01f), at = WorldPoint(-1f, -0.01f))

        val turn = drag.turnAbout(centre)

        assertTrue(kotlin.math.abs(turn) < 0.1f, "a step across the seam turned $turn radians")
        assertEquals(2f * kotlin.math.atan2(0.01f, 1f), turn, 1e-5f)
    }

    @Test
    fun `the seam is crossed the short way in the other direction too`() {
        val centre = WorldPoint(0f, 0f)
        // Just below -X to just above it: clockwise across the seam, where atan2 jumps by +2pi.
        val drag = Drag(start = WorldPoint(-1f, -0.01f), at = WorldPoint(-1f, 0.01f))

        val turn = drag.turnAbout(centre)

        assertEquals(-2f * kotlin.math.atan2(0.01f, 1f), turn, 1e-5f)
    }

    @Test
    fun `the distance between world points is the straight line in three dimensions`() {
        assertEquals(5f, WorldPoint(0f, 0f, 0f).distanceTo(WorldPoint(3f, 4f, 0f)))
        assertEquals(3f, WorldPoint(1f, 2f, 3f).distanceTo(WorldPoint(3f, 3f, 5f)))
    }
}
