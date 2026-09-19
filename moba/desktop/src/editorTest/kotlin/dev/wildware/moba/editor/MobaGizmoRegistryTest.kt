package dev.wildware.moba.editor

import dev.wildware.moba.Position
import dev.wildware.moba.PositionPositionGizmo
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.editor.gizmo.Drag
import dev.wildware.udea.editor.gizmo.FieldName
import dev.wildware.udea.editor.gizmo.FieldWrite
import dev.wildware.udea.editor.gizmo.GizmoTarget
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
    fun `the editor's registry lists the move gizmo generated from Position's handle`() {
        assertEquals(listOf<Any>(PositionPositionGizmo), MobaGizmoRegistry.gizmos)
    }

    @Test
    fun `dragging a unit's move handle writes its position and leaves the component alone`() {
        val unit = NetId.of(index = 12, generation = 0)
        val position = Position(x = 3f, y = 4f)
        val handle = PositionPositionGizmo.handles(GizmoTarget(unit, position, WorldPoint(3f, 4f))).single()

        assertEquals(WorldPoint(3f, 4f, 0f), handle.at)
        assertEquals(
            listOf(
                FieldWrite(unit, Position, FieldName("x"), 5f),
                FieldWrite(unit, Position, FieldName("y"), 3f),
            ),
            handle.drag(Drag(start = WorldPoint(3f, 4f), at = WorldPoint(5f, 3f))),
        )
        assertEquals(3f, position.x, "a drag moved the unit itself; it must only answer writes")
    }
}
