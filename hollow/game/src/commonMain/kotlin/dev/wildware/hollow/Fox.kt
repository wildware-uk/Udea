package dev.wildware.hollow

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.World
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Drawn
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.GameAssets
import kotlinx.serialization.Serializable

/**
 * What a fox is doing this tick (issue #251). The whole of its mind is which of these it is in.
 *
 * The order is the wire's: an enum crosses as its ordinal, and `net-protocol.lock` records the
 * constants in this order, so a new state goes on the end.
 */
@Serializable
public enum class FoxMode {
    /** Nobody is near: walking to a point of its own choosing, or standing and looking about. */
    Wander,

    /** A player is in range: running at the nearest one. */
    Chase,

    /** Hurt, with a player near: running directly away from the nearest one. */
    Flee,

    /**
     * Killed (issue #252): lying where it fell until `DeathSystem` takes it away. Last, because the
     * order is the wire's.
     */
    Dead,
}

/**
 * A creature in the clearing (issue #251): the Khronos fox, which wanders, chases a player who comes
 * within [FoxBrain.CHASE_RANGE], and runs away once its health is down to [FoxBrain.FLEE_AT].
 *
 * ## What is on the wire, and what is not
 *
 * [mode] and [target] are `@Net`. They are what a fox *is* to anybody watching it - which of its
 * states it is in and whom it is after - and a client that had to re-derive either would be running
 * a second copy of the state machine, free to disagree with the server's. Where the fox is, which
 * way it faces and which clip it plays reach a client in `Transform3D` and `Animator`, and how hurt
 * it is in `udea-gas`'s `Attributes` (issue #252), all of which replicate already.
 *
 * [decideAt], [goalX], [goalY] and [heading] are `@Sim`: captured, so a rewind restores a fox that
 * carries on to the same point on the same schedule, and never sent, because they are the working
 * memory of a decision only the server makes.
 *
 * ## Where the rest of the fox is
 *
 * As with a `Player`: `Transform3D` is where it stands, which a dynamic `PhysicsBody` drives, so a
 * fox is stopped by a tree and pushed by a player the way a player is. `Animator` is the clip,
 * which [FoxPoseSystem] keeps in step with [mode]. `Drawn` names the model, and `udea-render`'s
 * `ModelRenderSystem` attaches it on whichever machine draws the fox (issue #270).
 */
@Serializable
@Replicated
public class Fox(
    /** The tick a wandering fox next chooses where to go. Nothing is chosen before it. */
    @Sim public var decideAt: Tick = Tick(0L),
    /** Where a wandering fox is walking to, on the ground plane. */
    @Sim public var goalX: Float = 0f,
    /** @see goalX */
    @Sim public var goalY: Float = 0f,
    /**
     * Which way the fox is facing, in radians about Z, with no model offset in it.
     *
     * Held for the reason `Player.heading` is: the solver copies a dynamic body's angle over
     * `Transform3D.rotationZ` on every tick, so a facing written only while the fox moves would be
     * undone the moment it stopped.
     */
    @Sim public var heading: Float = 0f,
    /** Which state it is in. See [FoxMode]. */
    @Net public var mode: FoxMode = FoxMode.Wander,
    /** The player it is chasing or fleeing, or [NetId.NONE] while it wanders. */
    @Net public var target: NetId = NetId.NONE,
) : Component<Fox> {

    override fun type(): ComponentType<Fox> = Fox

    override fun toString(): String =
        "Fox($mode target=$target goal=($goalX, $goalY) decideAt=$decideAt heading=$heading)"

    public companion object : ComponentType<Fox>() {

        /** How wide a fox is on the ground plane: the circle a tree stops. */
        internal const val RADIUS: Float = 0.3f

        /**
         * The uniform scale the fox model is drawn at.
         *
         * The Khronos fox is in centimetre-like units, about 80 tall (moba's model shot measured
         * it), so this makes it a little under a metre at the shoulder beside a 1.8 metre human:
         * big for a fox, and readable from a camera six metres back.
         */
        internal const val SCALE: Float = 0.012f

        /**
         * Radians to add to a heading to point the model along it.
         *
         * The fox faces `-Y` once its glTF has been brought into Z-up, as the human does, so it
         * turns the same quarter turn.
         */
        internal const val MODEL_FACING: Float = 1.5707964f

        /**
         * The snapshot registration for `HollowNet`'s `ComponentRegistry`: what makes a snapshot,
         * a rewind and replication see a [Fox] at all. Built fresh per call, like
         * `Player.snapshotType()`.
         *
         * The kinds are in the generated replicator's order, which is the field names sorted:
         * `decideAt`, `goalX`, `goalY`, `heading`, `mode`, `target`. `ComponentSchema.of` refuses a
         * list of the wrong length; `FoxReplicationTest` sends every fox through it and compares
         * `mode` and `target` on both clients, so one of those typed wrong fails there.
         */
        internal fun snapshotType(): ReplicatedComponentType<Fox> = fleksComponentType(
            FoxReplicator,
            ComponentSchema.of(
                FoxReplicator,
                "Fox",
                listOf(
                    FieldKind.Tick,
                    FieldKind.Float,
                    FieldKind.Float,
                    FieldKind.Float,
                    FieldKind.Int,
                    FieldKind.NetId,
                ),
            ),
            Fox,
        ) { Fox() }

        /**
         * Puts a fox in the world at ([x], [y]), walking towards ([goalX], [goalY]) until
         * [decideAt], and hands back its [NetId].
         *
         * Straight onto the world, for the reason `Player.spawn` gives: every caller is either a
         * system inside a tick or a test between ticks. The body is dynamic, like a player's, so a
         * tree stops a fox and a fox and a player shove each other rather than overlap.
         */
        internal fun spawn(
            world: World,
            netIds: NetIdIndex,
            x: Float,
            y: Float,
            goalX: Float = x,
            goalY: Float = y,
            decideAt: Tick = Tick(0L),
        ): NetId {
            val entity = world.entity {
                it += Transform3D(x = x, y = y, scaleX = SCALE, scaleY = SCALE, scaleZ = SCALE)
                it += PhysicsBody(kind = BodyKind.Dynamic, x = x, y = y)
                it += Circle(RADIUS)
                it += Fox(decideAt = decideAt, goalX = goalX, goalY = goalY)
                it += Animator()
                // Named by the simulation, so a client draws the fox the server spawned without
                // Hollow writing a system to say so (issue #270).
                it += Drawn(GameAssets.models.fox, HollowAssets.registry)
            }
            return netIds.allocate(entity)
        }
    }
}
