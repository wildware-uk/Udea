package dev.wildware.udea.codegen

import com.github.quillraven.fleks.Component
import dev.wildware.udea.codegen.fixtures.Beacon
import dev.wildware.udea.codegen.fixtures.BeaconPositionGizmo
import dev.wildware.udea.codegen.fixtures.BeaconPositionTwin
import dev.wildware.udea.codegen.fixtures.BeaconReachRadiusGizmo
import dev.wildware.udea.codegen.fixtures.BeaconReachTwin
import dev.wildware.udea.codegen.fixtures.BeaconSightRangeGizmo
import dev.wildware.udea.codegen.fixtures.Crate
import dev.wildware.udea.codegen.fixtures.CratePositionGizmo
import dev.wildware.udea.codegen.fixtures.CrateHeadingTwin
import dev.wildware.udea.codegen.fixtures.CrateRotationGizmo
import dev.wildware.udea.codegen.fixtures.CrateSizeGizmo
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.editor.gizmo.Axis
import dev.wildware.udea.editor.gizmo.Drag
import dev.wildware.udea.editor.gizmo.DragConstraint
import dev.wildware.udea.editor.gizmo.FieldName
import dev.wildware.udea.editor.gizmo.FieldWrite
import dev.wildware.udea.editor.gizmo.Gizmo
import dev.wildware.udea.editor.gizmo.GizmoTarget
import dev.wildware.udea.editor.gizmo.HandleShape
import dev.wildware.udea.editor.gizmo.Plane
import dev.wildware.udea.editor.gizmo.Snap
import dev.wildware.udea.editor.gizmo.WorldPoint
import dev.wildware.udea.editor.gizmo.handles
import dev.wildware.udea.generated.CodegenFixturesGizmoRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gizmos `kspTest` generated from the handle annotations in `fixtures/Gizmos.kt`, run headless
 * as values (issue #233).
 *
 * Two kinds of claim. **A generated gizmo and its hand-written twin behave identically**: the same
 * handles, and the same field writes for the same drags - which is what "an annotation is only
 * shorthand for what a user could write by hand" means once it is checkable. And **each generated
 * gizmo does what its annotation says**, against numbers worked out here rather than read off the
 * generated code.
 */
class GeneratedGizmoTest {

    private val entity = NetId.of(index = 3, generation = 1)

    /** A drag that starts on the handle and moves by ([dx], [dy], [dz]). */
    private fun dragFrom(at: WorldPoint, dx: Float, dy: Float, dz: Float = 0f): Drag =
        Drag(start = at, at = WorldPoint(at.x + dx, at.y + dy, at.z + dz))

    /**
     * What a gizmo shows and what it writes, for [target], under each of [TWIN_DRAGS] - once grabbed
     * on the handle and once grabbed off it, as a person does, so a gizmo that ignored where the drag
     * began could not pass as its twin.
     */
    private fun <C : Component<C>> behaviour(gizmo: Gizmo<C>, target: GizmoTarget<C>): List<Any> =
        gizmo.handles(target).flatMap { handle ->
            val offCentre = WorldPoint(handle.at.x + GRAB.x, handle.at.y + GRAB.y, handle.at.z + GRAB.z)
            listOf(handle.at, handle.shape, handle.constraint) +
                TWIN_DRAGS.map { (dx, dy, dz) -> handle.drag(dragFrom(handle.at, dx, dy, dz)) } +
                TWIN_DRAGS.map { (dx, dy, dz) -> handle.drag(dragFrom(offCentre, dx, dy, dz)) }
        }

    // --- twins ---------------------------------------------------------------------------------

    @Test
    fun `the generated position gizmo and its hand-written twin show and write the same`() {
        val target = GizmoTarget(entity, Beacon(x = 4f, y = -2f), origin = WorldPoint(4f, -2f))

        assertEquals(behaviour(BeaconPositionTwin(), target), behaviour(BeaconPositionGizmo, target))
    }

    @Test
    fun `the generated radius gizmo and its hand-written twin show and write the same`() {
        val target = GizmoTarget(entity, Beacon(reach = 2.5f), origin = WorldPoint(10f, 20f))

        assertEquals(behaviour(BeaconReachTwin, target), behaviour(BeaconReachRadiusGizmo, target))
    }

    @Test
    fun `the generated rotation gizmo and its hand-written twin show and write the same`() {
        val target = GizmoTarget(entity, Crate(heading = 0.75f), origin = WorldPoint(-3f, 1f, 2f))

        assertEquals(behaviour(CrateHeadingTwin, target), behaviour(CrateRotationGizmo, target))
    }

    @Test
    fun `twins are compared on real output, so a gizmo that writes nothing could not pass`() {
        // The comparison above would pass for two gizmos that both declare nothing. These are the
        // two sides' non-empty answers, so an emitter that dropped the handle is a red test.
        val target = GizmoTarget(entity, Beacon(reach = 2.5f), origin = WorldPoint(0f, 0f))
        val writes = BeaconReachRadiusGizmo.handles(target).single().drag(dragFrom(WorldPoint(2.5f, 0f), 1f, 0f))

        assertEquals(listOf(FieldWrite(entity, Beacon, FieldName("reach"), 3.5f, Snap.Grid)), writes)
    }

    // --- what each annotation does ---------------------------------------------------------------

    @Test
    fun `a 2D position is the built-in move, on the entity, on the ground plane, moving it by the drag`() {
        val beacon = Beacon(x = 4f, y = -2f)
        val handles = BeaconPositionGizmo.handles(GizmoTarget(entity, beacon, WorldPoint(4f, -2f)))

        // Two axis arrows and the free square (issue #236), all on the entity.
        assertEquals(
            listOf(HandleShape.Arrow(Axis.X), HandleShape.Arrow(Axis.Y), HandleShape.PlaneSquare(Plane.XY)),
            handles.map { it.shape },
        )
        assertTrue(handles.all { it.at == WorldPoint(4f, -2f, 0f) }, "a move handle is not on the entity")
        val free = handles.last()
        assertEquals(DragConstraint.Across(Plane.XY), free.constraint)
        // Grabbed off-centre, at (5, -1): the offset cancels, and the beacon moves by the drag.
        val writes = free.drag(Drag(WorldPoint(5f, -1f), WorldPoint(8f, 3f)))
        assertEquals(
            listOf(
                FieldWrite(entity, Beacon, FieldName("x"), 7f, Snap.Grid),
                FieldWrite(entity, Beacon, FieldName("y"), 2f, Snap.Grid),
            ),
            writes,
        )
        assertEquals(4f, beacon.x, "the drag moved the component itself")
    }

    @Test
    fun `a 3D position handle drives the fields the annotation names, in the plane facing the view`() {
        val crate = Crate(px = 1f, py = 2f, pz = 3f)
        val handle = CratePositionGizmo.handles(GizmoTarget(entity, crate, WorldPoint(1f, 2f, 3f))).single()

        assertEquals(WorldPoint(1f, 2f, 3f), handle.at)
        assertEquals(HandleShape.Sphere, handle.shape)
        assertEquals(DragConstraint.ViewPlane, handle.constraint)
        assertEquals(
            listOf(
                FieldWrite(entity, Crate, FieldName("px"), 2f),
                FieldWrite(entity, Crate, FieldName("py"), 0f),
                FieldWrite(entity, Crate, FieldName("pz"), 7f),
            ),
            handle.drag(dragFrom(handle.at, 1f, -2f, 4f)),
        )
    }

    @Test
    fun `a size handle is the box corner, and dragging it grows the box about its centre`() {
        val crate = Crate(sizeX = 2f, sizeY = 4f, sizeZ = 6f)
        val handle = CrateSizeGizmo.handles(GizmoTarget(entity, crate, WorldPoint(10f, 10f, 10f))).single()

        assertEquals(WorldPoint(11f, 12f, 13f), handle.at)
        assertEquals(HandleShape.BoxCorner, handle.shape)
        assertEquals(DragConstraint.ViewPlane, handle.constraint, "a 3D size is dragged in the view's plane")
        // The corner moves out by 1 on X and in by 1 on Y: the box is 2 wider and 2 shorter.
        assertEquals(
            listOf(
                FieldWrite(entity, Crate, FieldName("sizeX"), 4f),
                FieldWrite(entity, Crate, FieldName("sizeY"), 2f),
                FieldWrite(entity, Crate, FieldName("sizeZ"), 6f),
            ),
            handle.drag(dragFrom(handle.at, 1f, -1f)),
        )
        // Grabbed 1 beyond the corner and dragged to the centre: the change is 4 on a width of 2,
        // and a size is never negative.
        assertEquals(0f, handle.drag(Drag(WorldPoint(12f, 12f, 13f), WorldPoint(10f, 12f, 13f))).first().value)
    }

    @Test
    fun `a rotation handle is a ring about the up axis, and turning it adds the turn to the heading`() {
        val crate = Crate(heading = 0.5f)
        val handle = CrateRotationGizmo.handles(GizmoTarget(entity, crate, WorldPoint(0f, 0f, 1f))).single()

        assertEquals(WorldPoint(0f, 0f, 1f), handle.at)
        assertEquals(HandleShape.Ring(Axis.Z), handle.shape)
        assertEquals(DragConstraint.Across(Plane.XY), handle.constraint)
        // A quarter turn anticlockwise: from +X to +Y.
        val written = handle.drag(Drag(WorldPoint(2f, 0f, 1f), WorldPoint(0f, 2f, 1f))).single()
        assertEquals(FieldName("heading"), written.field)
        assertEquals(0.5f + (Math.PI / 2).toFloat(), written.value, 1e-6f)
    }

    @Test
    fun `a radius and a range handle sit on the rim and set the distance from the entity, never below zero`() {
        val beacon = Beacon(reach = 2f, sight = 5f)
        val target = GizmoTarget(entity, beacon, WorldPoint(1f, 1f))
        val reach = BeaconReachRadiusGizmo.handles(target).single()
        val sight = BeaconSightRangeGizmo.handles(target).single()

        assertEquals(WorldPoint(3f, 1f), reach.at)
        assertEquals(WorldPoint(6f, 1f), sight.at)
        assertEquals(HandleShape.Point, reach.shape)
        assertEquals(HandleShape.Line(WorldPoint(1f, 1f)), sight.shape, "a range draws its spoke back to the entity")
        assertEquals(DragConstraint.Along(Axis.X), sight.constraint)
        assertEquals(8f, sight.drag(dragFrom(sight.at, 3f, 0f)).single().value)
        // Grabbed 4 out and dragged onto the entity: 2 less 4 is clamped to nothing.
        assertEquals(0f, reach.drag(Drag(WorldPoint(5f, 1f), WorldPoint(1f, 1f))).single().value)
    }

    // --- the registry --------------------------------------------------------------------------

    @Test
    fun `the gizmo registry lists every generated gizmo and every hand-written one, by class name`() {
        assertEquals(
            listOf(
                "BeaconPositionGizmo",
                "BeaconPositionTwin",
                "BeaconReachRadiusGizmo",
                "BeaconReachTwin",
                "BeaconSightRangeGizmo",
                "CrateHeadingTwin",
                "CratePositionGizmo",
                "CrateRotationGizmo",
                "CrateSizeGizmo",
            ).map { "dev.wildware.udea.codegen.fixtures.$it" },
            CodegenFixturesGizmoRegistry.gizmos.map { it.javaClass.name },
        )
        // A hand-written object is listed as itself; only a class is constructed.
        assertEquals(BeaconReachTwin, CodegenFixturesGizmoRegistry.gizmos[3])
    }

    private companion object {
        /** Where a person grabs a handle, relative to its centre: never quite on it. */
        val GRAB: WorldPoint = WorldPoint(0.3f, -0.2f, 0.1f)

        /** Moves along each axis, both ways, diagonally, and not at all. */
        val TWIN_DRAGS: List<Triple<Float, Float, Float>> = listOf(
            Triple(1f, 0f, 0f),
            Triple(-3.5f, 0f, 0f),
            Triple(0f, 2f, 0f),
            Triple(0f, -0.25f, 0f),
            Triple(1.5f, 1.5f, 0f),
            Triple(-2f, 4f, 0f),
            Triple(0f, 0f, 0f),
        )
    }
}
