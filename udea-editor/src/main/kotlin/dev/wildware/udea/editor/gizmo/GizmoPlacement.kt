package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World

/**
 * Where an entity is and which way it faces, as its game says (issue #236): what the editor puts each
 * [GizmoTarget]'s origin and local axes at.
 *
 * The engine has no one component that means "position" - `moba` keeps it in its own `Position`, a
 * physics game in `PhysicsBody`, a 3D one in `Transform3D` - so a game's editor says which, once, in
 * its `editor` source set. The editor asks it on the render thread, for each selected entity, every
 * frame the Scene tab draws; it reads the world and never changes it.
 */
public fun interface GizmoPlacement {

    /** Where [entity] is in [world], and its own axes; `null` for an entity nothing places, which gets no handles. */
    public fun place(world: World, entity: Entity): Placement?
}

/**
 * An entity's place, for its gizmos: its [origin], and the [axes] its handles follow when the
 * editor's axes switch is on local - its heading, for a 2D entity ([AxisFrame.heading]).
 */
public data class Placement(
    val origin: WorldPoint,
    val axes: AxisFrame = AxisFrame.WORLD,
)
