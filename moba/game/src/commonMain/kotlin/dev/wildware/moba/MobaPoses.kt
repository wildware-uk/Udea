package dev.wildware.moba

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.render.interp.Pose
import dev.wildware.udea.render.interp.PoseSource

/**
 * Where a `moba` unit is, for anything that draws or frames it.
 *
 * ## The lie this deletes
 *
 * `CameraRig` used to take an `Interpolator`, which reads `PhysicsBody`. A `moba` unit carries
 * [Position] and no physics body, so following one resolved the net id, found no pose, moved the
 * camera not at all, and `render.follow_entity` answered `{"following": <id>}` regardless.
 * `MobaScene`'s KDoc said so out loud - "**Following an entity does not work**" - and
 * `MobaAgent`'s said it again. An agent that then screenshots and sees the wrong part of the map
 * has no way to attribute it, which is the exact failure `CameraOutcome` exists to prevent and
 * the exact failure this game shipped.
 *
 * `CameraRig` names the capability now ([PoseSource]) rather than the physics implementation of
 * it, so this eight-line object is the whole fix. `followability` asks *this* whether it has a
 * pose, so an entity with no [Position] - a pure effect, say - is still refused rather than
 * accepted, and the refusal is now true rather than universal.
 *
 * ## No interpolation, stated rather than hidden
 *
 * It returns the simulated position and ignores `alpha`. `moba`'s units move in whole ticks
 * (`UnitBattleSystem` adds `kind.moveSpeed` per tick; `PlayerMovementSystem` does the same), and
 * there is no `Interp` component on them to interpolate *from* - `InterpSnapshotSystem` records
 * poses for `PhysicsBody` entities and this game has none. So a body moves in 60Hz steps and is
 * drawn in 60Hz steps, which on a 60Hz display is exactly right and on a 144Hz one is the
 * judder `Interp` exists to remove. Closing that means giving these units physics bodies, which
 * is a larger change than the camera needed and is not smuggled in here.
 */
public object PositionPoses : PoseSource {

    override fun poseOf(world: World, entity: Entity, alpha: Float, into: Pose): Boolean {
        with(world) {
            val position = entity.getOrNull(Position) ?: return false
            into.x = position.x
            into.y = position.y
            into.angle = 0f
            return true
        }
    }

    override fun toString(): String = "PositionPoses"
}
