package dev.wildware.hollow

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import kotlin.math.atan2
import kotlin.math.sqrt
import dev.wildware.udea.generated.Fox as FoxModel

/**
 * The numbers a fox's mind is made of (issue #251). Distances in metres on the ground plane, speeds
 * in metres a second - the solver's unit, as `HollowMovement` says for a player's - and every
 * duration a count of ticks.
 */
internal object FoxBrain {

    /** A fox with a player this close, or closer, runs at the nearest one. */
    const val CHASE_RANGE: Float = 8f

    /**
     * A chasing fox keeps chasing until its player is further than this.
     *
     * Wider than [CHASE_RANGE] so a player standing on the edge of range does not flick a fox
     * between chasing and wandering every tick; a fox has to be *out-run* to be shaken off.
     */
    const val GIVE_UP_RANGE: Float = 11f

    /**
     * A fox at or below this health runs from a player instead of at one. Hit points of the
     * `hollow.health` attribute, which is a `Float` because `udea-gas` holds every attribute as one.
     */
    const val FLEE_AT: Float = 30f

    /** A hurt fox runs from any player this close, and stops fleeing once it is further than this. */
    const val FLEE_RANGE: Float = GIVE_UP_RANGE

    /**
     * A chasing fox this close to its player stops and stands at it.
     *
     * Two bodies' worth apart, so a fox stands at a player's heels rather than shoving at them for
     * ever, and inside `CombatRules.BITE_REACH`, so one standing there bites (issue #252).
     */
    const val BITE_RANGE: Float = 1.1f

    /** How fast a fox walks while it wanders. Slower than a person walks. */
    const val WALK_SPEED: Float = 1.6f

    /**
     * How fast a fox runs, chasing or fleeing: faster than a person walks and slower than one runs,
     * so a player can always get away from a fox by running, and never by walking.
     */
    const val RUN_SPEED: Float = 4.4f

    /** A wandering fox this close to its goal has arrived, and stands. */
    const val ARRIVED: Float = 0.25f

    /** How far from where it stands a wandering fox chooses to go, at most, on each axis. */
    const val WANDER_REACH: Float = 4f

    /**
     * How far from the middle of the clearing a wandering fox will choose to go: inside the inner
     * ring of trees, which stands from 14 metres out.
     */
    const val ROAM_RADIUS: Float = 12f

    /** The shortest time a wandering fox keeps a goal before it chooses another. */
    val DECIDE_EVERY: Ticks = Ticks(150L)

    /** The most extra ticks, beyond [DECIDE_EVERY], a fox may keep a goal. Drawn from the `AI` stream. */
    const val DECIDE_SPREAD: Int = 150

    /**
     * Below this speed, in metres a second, a fox is standing and plays the survey.
     *
     * Not zero, for the reason `PlayerPoseSystem.IDLE_BELOW` gives: a body at rest against another
     * keeps a little contact jitter.
     */
    const val IDLE_BELOW: Float = 0.2f

    /** How long a change of clip takes. The same fifth of a second a player's gait change takes. */
    val FADE: Ticks = Ticks(12L)

    /**
     * How far a dead fox is rolled about X, in radians: a quarter turn, onto its side (issue #252).
     * Presentation carried in simulation state because the pose is what replicates.
     */
    const val FALLEN: Float = 1.5707964f
}

/**
 * A fox's mind, once a tick, for every fox: which state it is in, and the velocity that state asks
 * for (issue #251).
 *
 * ## A state machine over the tick, with nothing remembered but the component
 *
 * Every input is in the world at the top of the tick - where the foxes and the players stand, each
 * fox's [Fox] - and every output is written back into it: the fox's `mode`, `target`, `heading` and
 * wander goal, and its `PhysicsBody`'s velocity. Nothing is held in this system between ticks, so a
 * rewind that restores the components restores the foxes' minds with them, and a replay steps the
 * same decisions from the same state.
 *
 * The states, in the order they are tested:
 *
 * | State | When | Does |
 * |---|---|---|
 * | [FoxMode.Flee] | health at or below [FoxBrain.FLEE_AT], and a player within [FoxBrain.FLEE_RANGE] | runs straight away from the nearest player |
 * | [FoxMode.Chase] | a player within [FoxBrain.CHASE_RANGE], or already chasing one within [FoxBrain.GIVE_UP_RANGE] | runs at the nearest player, and stands once within [FoxBrain.BITE_RANGE] |
 * | [FoxMode.Wander] | otherwise | walks to a goal it chose, stands there, and chooses again at `decideAt` |
 *
 * Before all three, [FoxMode.Dead]: a fox whose health has reached zero stands where it fell, and no
 * rule takes it out of that state (issue #252). A player whose health has reached zero is nobody's
 * nearest player, so a fox that has killed the one it was chasing turns to the next, or wanders.
 *
 * ## No trigonometry decides where a fox goes
 *
 * `determinism-audit.md` section 3.1 found `Math.sin` disagreeing with `StrictMath.sin` in the last
 * bit on 3.4% of sampled inputs on one JVM, and asks for `StrictMath`, a table or fixed point
 * wherever a trig call is unavoidable in authoritative state. Here it is avoidable, so it is
 * avoided: a fox steers by the **vector** to where it is going, made unit length with `sqrt`, which
 * IEEE-754 rounds exactly and the audit measured agreeing on every input. There is no angle in the
 * steering at all, so there is no `sin` or `cos` to disagree.
 *
 * The one angle is [Fox.heading], from [atan2], and it only turns the model - the reasoning
 * `PlayerPoseSystem` sets out at length for the player's, and it holds here word for word: it is
 * written to `Transform3D.rotationZ`, the collision shape is a circle, and nothing reads the heading
 * back to decide a velocity.
 *
 * ## Why here, and in this phase
 *
 * `SimPhase.Movement`, like `PlayerMovementSystem`: a velocity has to be on the body before
 * `SimPhase.Physics` steps it, and the positions it decides from are the ones the last tick's solve
 * left, the same positions every player's movement this tick decided from.
 */
internal class FoxBrainSystem : SimSystem() {

    private val foxes: Family = world.family { all(Fox, Transform3D, PhysicsBody) }

    private val players: Family = world.family { all(Player, Transform3D) }

    private val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]

    /** Whose health is what: a fox's decides whether it flees, a player's whether it is chased. */
    private val combat: HollowCombat = ctx[HollowCombat.KEY]

    /** The nearest player to the fox being decided. Reused, because this is a per-tick path. */
    private val nearest = Nearest()

    override fun onTick() {
        val now = tick
        foxes.forEach { entity ->
            val fox = entity[Fox]
            val at = entity[Transform3D]
            val body = entity[PhysicsBody]
            findNearest(at.x, at.y)
            val mode = modeFor(fox, entity.getOrNull(Attributes))
            if (mode != fox.mode && mode == FoxMode.Wander) {
                // Back to wandering: choose afresh now, rather than walk to a goal chosen before the
                // chase that may be the other side of the clearing.
                fox.decideAt = now
                fox.goalX = at.x
                fox.goalY = at.y
            }
            fox.mode = mode
            fox.target = if (mode == FoxMode.Wander || mode == FoxMode.Dead) NetId.NONE else nearest.id
            when (mode) {
                FoxMode.Dead -> stand(body)
                FoxMode.Chase -> {
                    if (nearest.distanceSquared <= BITE_SQUARED) {
                        stand(body)
                    } else {
                        steer(fox, body, nearest.x - at.x, nearest.y - at.y, FoxBrain.RUN_SPEED)
                    }
                }
                FoxMode.Flee -> steer(fox, body, at.x - nearest.x, at.y - nearest.y, FoxBrain.RUN_SPEED)
                FoxMode.Wander -> wander(fox, body, at, now)
            }
        }
    }

    /**
     * Which state [fox] is in this tick, given [nearest] and its [attributes] - null only for a fox
     * spawned since `ArmSystem` last ran, which is at full health by definition.
     */
    private fun modeFor(fox: Fox, attributes: Attributes?): FoxMode {
        if (fox.mode == FoxMode.Dead || (attributes != null && combat.isDead(attributes))) return FoxMode.Dead
        if (nearest.id == NetId.NONE) return FoxMode.Wander
        val squared = nearest.distanceSquared
        if (attributes != null && attributes.base(combat.health) <= FoxBrain.FLEE_AT) {
            return if (squared <= FLEE_SQUARED) FoxMode.Flee else FoxMode.Wander
        }
        if (squared <= CHASE_SQUARED) return FoxMode.Chase
        if (fox.mode == FoxMode.Chase && squared <= GIVE_UP_SQUARED) return FoxMode.Chase
        return FoxMode.Wander
    }

    /** Walks [fox] to its goal, choosing a new one at `decideAt`, and stands it once it arrives. */
    private fun wander(fox: Fox, body: PhysicsBody, at: Transform3D, now: Tick) {
        if (now >= fox.decideAt) choose(fox, at, now)
        val dx = fox.goalX - at.x
        val dy = fox.goalY - at.y
        if (dx * dx + dy * dy <= ARRIVED_SQUARED) {
            stand(body)
        } else {
            steer(fox, body, dx, dy, FoxBrain.WALK_SPEED)
        }
    }

    /**
     * A new goal for [fox], from the `AI` stream: up to [FoxBrain.WANDER_REACH] from where it
     * stands on each axis, pulled back inside [FoxBrain.ROAM_RADIUS] of the middle of the clearing,
     * and a new time to choose again.
     */
    private fun choose(fox: Fox, at: Transform3D, now: Tick) {
        var goalX = at.x + (ctx.rng.nextFloat(RngStream.AI) * 2f - 1f) * FoxBrain.WANDER_REACH
        var goalY = at.y + (ctx.rng.nextFloat(RngStream.AI) * 2f - 1f) * FoxBrain.WANDER_REACH
        val squared = goalX * goalX + goalY * goalY
        if (squared > ROAM_SQUARED) {
            val scale = FoxBrain.ROAM_RADIUS / sqrt(squared)
            goalX *= scale
            goalY *= scale
        }
        fox.goalX = goalX
        fox.goalY = goalY
        fox.decideAt = now + (FoxBrain.DECIDE_EVERY.count + ctx.rng.nextInt(RngStream.AI, FoxBrain.DECIDE_SPREAD))
    }

    /** Sets [body] moving along ([dx], [dy]) at [speed], and turns [fox] to face that way. */
    private fun steer(fox: Fox, body: PhysicsBody, dx: Float, dy: Float, speed: Float) {
        val length = sqrt(dx * dx + dy * dy)
        if (length == 0f) {
            stand(body)
            return
        }
        body.linearX = dx / length * speed
        body.linearY = dy / length * speed
        fox.heading = atan2(dy, dx)
    }

    /**
     * Stops [body]. Every tick a fox is not moving, for the reason `PlayerMovementSystem` gives: the
     * ground plane has no friction, and a body left alone coasts on its last velocity.
     */
    private fun stand(body: PhysicsBody) {
        body.linearX = 0f
        body.linearY = 0f
    }

    /**
     * Fills [nearest] with the living player closest to ([x], [y]), or [NetId.NONE] when there is
     * none.
     *
     * A tie goes to the player met first in the family, which is the same on every run of the same
     * world: Fleks orders a family by entity id, and ids are allocated in the order the world was
     * built.
     */
    private fun findNearest(x: Float, y: Float) {
        nearest.id = NetId.NONE
        nearest.distanceSquared = Float.MAX_VALUE
        players.forEach { player ->
            val health = player.getOrNull(Attributes)
            if (health != null && combat.isDead(health)) return@forEach
            val at = player[Transform3D]
            val dx = at.x - x
            val dy = at.y - y
            val squared = dx * dx + dy * dy
            if (squared < nearest.distanceSquared) {
                nearest.id = netIds.netIdOf(player)
                nearest.distanceSquared = squared
                nearest.x = at.x
                nearest.y = at.y
            }
        }
    }

    override fun toString(): String = "FoxBrainSystem"

    /** The player nearest the fox being decided: who, how far squared, and where. */
    private class Nearest {
        var id: NetId = NetId.NONE
        var distanceSquared: Float = Float.MAX_VALUE
        var x: Float = 0f
        var y: Float = 0f
    }

    private companion object {
        // Squared, so no range test takes a square root: `sqrt` is exact, but a comparison that
        // needs none has nothing to round.
        const val CHASE_SQUARED = FoxBrain.CHASE_RANGE * FoxBrain.CHASE_RANGE
        const val GIVE_UP_SQUARED = FoxBrain.GIVE_UP_RANGE * FoxBrain.GIVE_UP_RANGE
        const val FLEE_SQUARED = FoxBrain.FLEE_RANGE * FoxBrain.FLEE_RANGE
        const val BITE_SQUARED = FoxBrain.BITE_RANGE * FoxBrain.BITE_RANGE
        const val ARRIVED_SQUARED = FoxBrain.ARRIVED * FoxBrain.ARRIVED
        const val ROAM_SQUARED = FoxBrain.ROAM_RADIUS * FoxBrain.ROAM_RADIUS
    }
}

/**
 * Turns every fox to its heading and plays the clip its state asks for (issue #251): the survey
 * while it stands, the walk while it wanders, the run while it chases or flees.
 *
 * `SimPhase.PostPhysics`, for the reason `PlayerPoseSystem` gives: the solver has just copied each
 * dynamic body's angle over `Transform3D.rotationZ`, so the facing has to be written after it, and
 * whether the fox is standing is what the solver actually did - a fox pressed against a tree it is
 * trying to walk through is standing, and surveys.
 */
internal class FoxPoseSystem : SimSystem() {

    private val foxes: Family = world.family { all(Fox, PhysicsBody, Transform3D, Animator) }

    override fun onTick() {
        val now = tick
        foxes.forEach { entity -> pose(entity, now) }
    }

    private fun pose(entity: Entity, now: Tick) {
        val fox = entity[Fox]
        val transform = entity[Transform3D]
        transform.rotationZ = fox.heading + Fox.MODEL_FACING
        val animator = entity[Animator]
        if (fox.mode == FoxMode.Dead) {
            // Issue #252. The Khronos fox has no death clip - Survey, Walk and Run are all it has -
            // so a dead fox is a pose: rolled onto its side, the survey frozen on its first frame.
            // `Transform3D` and `Animator` both replicate, so a client sees it fall.
            transform.rotationX = FoxBrain.FALLEN
            if (!animator.isPlaying(FoxModel.Clips.Survey) || animator.current.speed != 0f) {
                animator.play(FoxModel.Clips.Survey, now, speed = 0f)
            }
            return
        }
        val body = entity[PhysicsBody]
        val speed = sqrt(body.linearX * body.linearX + body.linearY * body.linearY)
        val wanted = clipFor(fox.mode, speed)
        if (!animator.isPlaying(wanted)) animator.crossfade(wanted, now, over = FoxBrain.FADE)
    }

    /** The clip a fox in [mode] moving at [speed] plays. */
    private fun clipFor(mode: FoxMode, speed: Float): AnimationClip = when {
        speed < FoxBrain.IDLE_BELOW -> FoxModel.Clips.Survey
        mode == FoxMode.Wander -> FoxModel.Clips.Walk
        else -> FoxModel.Clips.Run
    }

    override fun toString(): String = "FoxPoseSystem"
}
