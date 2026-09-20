package dev.wildware.hollow

import com.github.quillraven.fleks.Family
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.Human
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * How a character is turned and which of its three clips is playing (issue #250).
 *
 * ## Two questions, two sources, and the difference matters
 *
 * The **heading** comes from what the player asked for, and is remembered in `Player.heading` so it
 * persists while they stand still. The **animation** comes from what the solver actually did. A
 * character walking into a rock therefore keeps facing the rock and stands still on the spot, which
 * is what a player expects; deriving both from the same number would give either a character that
 * turns away from the rock it is pushing, or one that runs on the spot against it.
 *
 * ## Why it runs after the solver
 *
 * `Transform3DFromBodySystem` copies a dynamic body's solved pose - position *and* angle - into its
 * `Transform3D` at the end of `SimPhase.Physics`. A heading written before that would be thrown
 * away, and the angle that replaced it would be whatever torque a contact happened to put on a
 * circle. So this is `SimPhase.PostPhysics`: physics owns where the character is, and the game owns
 * which way it is looking.
 *
 * ## The one trig call in Hollow's simulation
 *
 * [atan2] turns the move axis into a heading. `determinism-audit.md` bans recovering an angle from
 * a vector in authoritative state *as a class of call* - section 3.1's table, the
 * `Vector2.angleDeg()` row - and the reason it gives is that `Math.atan2` is permitted two ulp of
 * error and is not specified to agree between implementations. Three things are true here and are
 * written down rather than assumed:
 *
 * - the audit's own probe measured `Math.atan2` and `StrictMath.atan2` agreeing on **all** two
 *   million sampled inputs on this JDK, while `sin` differed on 3.4% of them;
 * - `hollow:game` targets the JVM and Android and nothing else, so there is no third libm in play;
 * - and, decisively, **the result feeds nothing but which way a model points.** It is written to
 *   `Transform3D.rotationZ` and read by the renderer. It does not move the character - the velocity
 *   comes from the axis, not from the heading - and the collision shape is a circle, so it cannot
 *   change what the character bumps into either. A last-bit difference between two machines is a
 *   last-bit difference in a rotation, and can never become a difference in a trajectory.
 *
 * The one thing it *can* do is put a last bit into a replay hash, because `Transform3D` is
 * captured. Hollow is not in the cross-platform `replay-equality` gate today; when it joins, in the
 * hardening ticket, this is the line to look at first.
 */
public class PlayerPoseSystem : SimSystem() {

    private val players: Family = world.family { all(Player, PhysicsBody, Transform3D, Animator) }

    override fun onTick() {
        val now = tick
        players.forEach { entity ->
            val player = entity[Player]
            if (player.moveX != 0f || player.moveY != 0f) {
                player.heading = atan2(player.moveY, player.moveX)
            }
            // Every tick, not only the moving ones: `Transform3DFromBodySystem` has just copied the
            // body's solved angle over `rotationZ`, and a character that stopped would otherwise
            // swing back to whatever torque a contact had put on its circle.
            entity[Transform3D].rotationZ = player.heading + Player.MODEL_FACING
            val body = entity[PhysicsBody]
            val animator = entity[Animator]
            val wanted = clipFor(sqrt(body.linearX * body.linearX + body.linearY * body.linearY))
            if (!animator.isPlaying(wanted)) animator.crossfade(wanted, now, over = FADE)
        }
    }

    /** Which clip a character moving at [speed] world units a second is playing. */
    private fun clipFor(speed: Float): AnimationClip = when {
        speed < IDLE_BELOW -> Human.Clips.Idle
        speed < RUN_AT -> Human.Clips.Walk
        else -> Human.Clips.Run
    }

    public companion object {

        /**
         * Below this, in units a second, the character is standing.
         *
         * Not zero: a body resting against a rock keeps a few thousandths of a unit a second of
         * contact jitter, and a threshold of zero would flicker between idle and walk on it.
         */
        public const val IDLE_BELOW: Float = 0.2f

        /**
         * At or above this, in units a second, the character is running.
         *
         * Halfway between [HollowMovement.WALK_SPEED] and [HollowMovement.RUN_SPEED], so a
         * character that is being slowed by something it is pushing keeps the gait it asked for
         * until it has lost most of its speed.
         */
        public const val RUN_AT: Float = (HollowMovement.WALK_SPEED + HollowMovement.RUN_SPEED) / 2f

        /**
         * How long a change of gait takes.
         *
         * A fifth of a second, in ticks, which is what `moba`'s model shot found reads as a change
         * of gait rather than a cut. A `Ticks` and not a float of seconds: everything about an
         * `Animator` is denominated in ticks so that a client, a server and a replay measure a fade
         * the same way.
         */
        public val FADE: Ticks = Ticks(12L)
    }
}
