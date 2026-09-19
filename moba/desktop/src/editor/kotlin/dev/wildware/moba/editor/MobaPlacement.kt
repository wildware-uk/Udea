package dev.wildware.moba.editor

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.moba.Position
import dev.wildware.udea.editor.gizmo.GizmoPlacement
import dev.wildware.udea.editor.gizmo.Placement
import dev.wildware.udea.editor.gizmo.WorldPoint

/**
 * Where a `moba` entity is, for its gizmos (issue #236): its [Position], on the ground plane.
 *
 * Nothing in `moba` turns - a unit faces left or right by flipping its sprite - so an entity's own
 * axes are the world's, and the editor's world/local switch changes nothing here. An entity with no
 * `Position` gets no handles.
 */
internal object MobaPlacement : GizmoPlacement {

    override fun place(world: World, entity: Entity): Placement? {
        val position = with(world) { entity.getOrNull(Position) } ?: return null
        return Placement(WorldPoint(position.x, position.y))
    }
}
