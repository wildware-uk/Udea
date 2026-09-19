package dev.wildware.moba.editor

import dev.wildware.moba.Position
import dev.wildware.moba.PositionPositionGizmo
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.editor.gizmo.Axis
import dev.wildware.udea.editor.gizmo.Drag
import dev.wildware.udea.editor.gizmo.FieldName
import dev.wildware.udea.editor.gizmo.FieldWrite
import dev.wildware.udea.editor.gizmo.GizmoTarget
import dev.wildware.udea.editor.gizmo.HandleShape
import dev.wildware.udea.editor.gizmo.Plane
import dev.wildware.udea.editor.gizmo.Snap
import dev.wildware.udea.editor.gizmo.WorldPoint
import dev.wildware.udea.editor.gizmo.handles
import dev.wildware.udea.generated.MobaGizmoRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `moba`'s gizmos as the game's own build generates them (issue #233): the handle is declared on
 * `Position` in `:moba:game`, and the gizmo exists only in this project's `editor` source set.
 *
 * This is the route a real game takes, across two modules: `:moba:game`'s KSP run lists `Position`
 * on `MobaModuleRegistry`'s `@HandleIndex`, and the editor source set's run reads that off the
 * compiled registry and generates the gizmo here. `udea-codegen`'s own tests run both halves in one
 * source set; this is the one place they run in two.
 */
class MobaGizmoRegistryTest {

    @Test
    fun `the editor's registry lists the move gizmo generated from Position's handle, and the tower's range ring`() {
        // Sorted by name: the generated gizmo sits beside `Position`, the hand-written one in the editor's package.
        assertEquals(listOf<Any>(PositionPositionGizmo, TowerRangeGizmo), MobaGizmoRegistry.gizmos)
    }

    @Test
    fun `dragging a unit's move handle writes its position and leaves the component alone`() {
        val unit = NetId.of(index = 12, generation = 0)
        val position = Position(x = 3f, y = 4f)
        // The built-in move gizmo: an arrow along X, one along Y, and the free square between them.
        val (alongX, alongY, free) = PositionPositionGizmo.handles(GizmoTarget(unit, position, WorldPoint(3f, 4f)))

        assertEquals(listOf(WorldPoint(3f, 4f, 0f)), listOf(alongX.at, alongY.at, free.at).distinct())
        assertEquals(HandleShape.Arrow(Axis.X), alongX.shape)
        assertEquals(HandleShape.Arrow(Axis.Y), alongY.shape)
        assertEquals(HandleShape.PlaneSquare(Plane.XY), free.shape)
        assertEquals(
            listOf(
                FieldWrite(unit, Position, FieldName("x"), 5f, Snap.Grid),
                FieldWrite(unit, Position, FieldName("y"), 3f, Snap.Grid),
            ),
            free.drag(Drag(start = WorldPoint(3f, 4f), at = WorldPoint(5f, 3f))),
        )
        assertEquals(3f, position.x, "a drag moved the unit itself; it must only answer writes")
    }
}
