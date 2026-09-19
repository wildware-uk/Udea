package dev.wildware.moba.editor

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.moba.Position
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.editor.gizmo.AxisFrame
import dev.wildware.udea.editor.gizmo.GizmoPlacement
import dev.wildware.udea.editor.gizmo.Placement
import dev.wildware.udea.editor.gizmo.WorldPoint

/**
 * Where a `moba` entity is, for its gizmos (issue #236): its [Position], on the ground plane.
 *
 * Nothing in `moba` turns - a unit faces left or right by flipping its sprite - so an entity's own
 * axes are the world's, and the editor's world/local switch changes nothing here.
 *
 * A 3D model - the editor's Fox - is placed by its [Transform3D] instead (issue #237): at its point,
 * turned by its three angles, so local axes follow it. An entity with neither gets no handles.
 */
internal object MobaPlacement : GizmoPlacement {

    override fun place(world: World, entity: Entity): Placement? = with(world) {
        entity.getOrNull(Transform3D)?.let { t ->
            return Placement(WorldPoint(t.x, t.y, t.z), AxisFrame.euler(t.rotationX, t.rotationY, t.rotationZ))
        }
        val position = entity.getOrNull(Position) ?: return null
        return Placement(WorldPoint(position.x, position.y))
    }
}
