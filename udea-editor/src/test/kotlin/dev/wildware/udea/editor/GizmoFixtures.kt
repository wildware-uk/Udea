package dev.wildware.udea.editor

import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.agentComponent
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.BoxReplicator
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.CircleReplicator
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.physics.PhysicsBodyReplicator
import dev.wildware.udea.editor.gizmo.AxisFrame
import dev.wildware.udea.editor.gizmo.Gizmo
import dev.wildware.udea.editor.gizmo.GizmoPlacement
import dev.wildware.udea.editor.gizmo.GizmoRegistry
import dev.wildware.udea.editor.gizmo.GizmoScope
import dev.wildware.udea.editor.gizmo.GizmoTarget
import dev.wildware.udea.editor.gizmo.Placement
import dev.wildware.udea.editor.gizmo.WorldPoint
import dev.wildware.udea.editor.gizmo.moveHandles
import dev.wildware.udea.editor.gizmo.radiusHandle
import dev.wildware.udea.editor.gizmo.rotationHandle
import dev.wildware.udea.editor.gizmo.sizeHandles

/*
 * The gizmo tests' world (issue #236): `udea-core`'s own physics components, each given a gizmo
 * written by hand against the public API alone - exactly what a game's `editor` source set holds.
 *
 * Core's components because they are the ones in reach with a generated `Replicator`, which is what
 * `editor.begin_edit` needs to address a field. A [PhysicsBody] is a position and a heading, a
 * [Circle] a radius. A [Box] stores half extents, and [BoxGizmo] hands those two fields to the
 * built-in resize as if they were a width and a height: what the numbers mean to physics does not
 * matter here, only that a drag on a corner changes both by what the built-in says.
 */

/** Moves and turns a body: the built-in move on `x` and `y`, and the built-in ring on `angle`. */
internal object BodyGizmo : Gizmo<PhysicsBody> {
    override val component: ComponentType<PhysicsBody> = PhysicsBody

    override fun GizmoScope<PhysicsBody>.build(target: GizmoTarget<PhysicsBody>) {
        val body = target.component
        rotationHandle(target, PhysicsBody::angle, body.angle)
        moveHandles(target, PhysicsBody::x, body.x, PhysicsBody::y, body.y)
    }
}

/** Resizes a box: the built-in corners and sides on its two fields. */
internal object BoxGizmo : Gizmo<Box> {
    override val component: ComponentType<Box> = Box

    override fun GizmoScope<Box>.build(target: GizmoTarget<Box>) {
        sizeHandles(target, Box::halfWidth, target.component.halfWidth, Box::halfHeight, target.component.halfHeight)
    }
}

/** A circle's radius: the built-in rim grip. */
internal object CircleGizmo : Gizmo<Circle> {
    override val component: ComponentType<Circle> = Circle

    override fun GizmoScope<Circle>.build(target: GizmoTarget<Circle>) {
        radiusHandle(target, Circle::radius, target.component.radius)
    }
}

/** What a game's generated `<Game>GizmoRegistry` is: every gizmo, here the three above. */
internal object FixtureGizmos : GizmoRegistry {
    override val gizmos: List<Gizmo<*>> = listOf(BodyGizmo, BoxGizmo, CircleGizmo)
}

/** A body is where its `x` and `y` are, facing its `angle`: what places every handle in this world. */
internal object BodyPlacement : GizmoPlacement {
    override fun place(world: World, entity: Entity): Placement? {
        val body = with(world) { entity.getOrNull(PhysicsBody) } ?: return null
        return Placement(WorldPoint(body.x, body.y), AxisFrame.heading(body.angle))
    }
}

/** The three components as the tool surface knows them, so an edit session can address their fields. */
internal fun fixtureComponents(): List<AgentComponentType> = listOf(
    agentComponent("PhysicsBody", PhysicsBodyReplicator, PhysicsBody),
    agentComponent("Box", BoxReplicator, Box),
    agentComponent("Circle", CircleReplicator, Circle),
)
