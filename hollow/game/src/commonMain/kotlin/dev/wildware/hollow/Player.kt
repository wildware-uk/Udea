package dev.wildware.hollow

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentState
import kotlinx.serialization.Serializable

/**
 * A human somebody is playing, and what they asked it to do this tick (issue #250).
 *
 * ## Three fields of intent and one of identity
 *
 * [moveX], [moveY] and [running] are written at `SimPhase.Intent` by [PlayerControlSystem] and read
 * at `SimPhase.Movement` by [PlayerMovementSystem], and nothing else writes them. That split is
 * what makes a tick's movement a pure function of (state, intent): replaying a tick means restoring
 * the state and re-supplying these, with no device, no frame rate and no wall clock in it.
 *
 * They are `@Sim` rather than `@Net` because a client is the **source** of its own intent and the
 * server is the source of everybody's position: sending the axis back down would be echoing an
 * input. They are captured, though, because the component's *presence* is what marks an entity as
 * driven, and a rewind that dropped it would leave a character nobody can steer.
 *
 * [owner] is the one field on the wire, and a two-player game cannot be played without it: a client
 * has to know **which of these is mine** to point its camera at it and to predict it. Nothing else
 * replicated says so - a `NetId` is allocation order, which a client cannot predict.
 *
 * ## Where the rest of the character is
 *
 * Nothing here says where the player *is*: that is `Transform3D`, which the character's dynamic
 * `PhysicsBody` drives (issue #247). Nothing here says what is drawn either: that is a
 * `ModelRenderer` the presentation side attaches, and an `Animator`, which [PlayerPoseSystem]
 * moves between idle, walk and run.
 */
@Serializable
@Replicated
public class Player(
    /**
     * Which way the character is looking, in radians about Z, with no model offset in it.
     *
     * Held rather than derived, and that is not a saving. `Transform3DFromBodySystem` copies a
     * dynamic body's solved angle into `Transform3D.rotationZ` on **every** tick, so a heading
     * written only on the ticks a player was moving would be undone by the solver the moment they
     * stopped - the character would face east again the instant a key went up. [PlayerPoseSystem]
     * therefore writes `rotationZ` from this every tick and updates this only when the character is
     * moving, which is also what makes a facing persist across a rewind.
     */
    @Sim public var heading: Float = 0f,
    /** East-west axis this tick, `-1..1`, already clamped to the unit circle with [moveY]. */
    @Sim public var moveX: Float = 0f,
    /** North-south axis this tick, `-1..1`. Positive is north, `+Y` on the ground plane. */
    @Sim public var moveY: Float = 0f,
    /** Which connection drives this character: a [PeerId.raw], or [UNOWNED] for nobody's. */
    @Net public var owner: Int = UNOWNED,
    /** Whether the run control was held this tick. See [HollowMovement.speed]. */
    @Sim public var running: Boolean = false,
) : Component<Player> {

    override fun type(): ComponentType<Player> = Player

    override fun toString(): String =
        "Player(move=($moveX, $moveY) heading=$heading running=$running owner=$owner)"

    public companion object : ComponentType<Player>() {

        /**
         * [owner] for a character no connection drives.
         *
         * [PeerId.SERVER]'s raw value: the server owns whatever nobody else does, and a character
         * whose player left stands in the clearing until somebody takes it over.
         */
        public const val UNOWNED: Int = 0

        /** How wide a character is on the ground plane: the circle a rock stops. */
        public const val RADIUS: Float = 0.35f

        /**
         * The uniform scale the human model is drawn at.
         *
         * The converted character is about 553 units tall - an 8-unit mesh under a node the FBX
         * scales by 69.18 - so this makes it 1.8 metres, which is what the clearing is built at.
         */
        public const val SCALE: Float = 0.00325f

        /**
         * Radians to add to a heading to point the model along it.
         *
         * The Quaternius human faces `-Y` in its own file, so a character walking east - heading
         * `0` - has to be turned a quarter turn anticlockwise for its front to lead.
         */
        public const val MODEL_FACING: Float = 1.5707964f

        /**
         * The snapshot registration for [dev.wildware.hollow.net.HollowNet]'s `ComponentRegistry`,
         * which is what makes a snapshot, a rewind and replication see a [Player] at all: capture
         * walks the registry, and a component left out of it is invisible rather than partly
         * captured. Built fresh per call, like `Transform3D.snapshotType()`.
         *
         * The kinds are in the generated replicator's order, which is the field names sorted:
         * `heading`, `moveX`, `moveY`, `owner`, `running`. `ComponentSchema.of` refuses a list of
         * the wrong length, and `PlayerReplicationTest` round-trips one to catch a kind typed wrong
         * at the right length.
         *
         * A client receives the whole component through its `allMask`, so the four `@Sim` fields
         * arrive from columns the server never filled - a zeroed axis, a zero heading and a
         * `running` of false. That is the right value on a client: it does not step the simulation,
         * the axis is an input that only the machine owning the character produces, and which way
         * the character is looking reaches it in `Transform3D.rotationZ`, which is `@Net`.
         */
        public fun snapshotType(): ReplicatedComponentType<Player> = fleksComponentType(
            PlayerReplicator,
            ComponentSchema.of(
                PlayerReplicator,
                "Player",
                listOf(FieldKind.Float, FieldKind.Float, FieldKind.Float, FieldKind.Int, FieldKind.Bool),
            ),
            Player,
        ) { Player() }

        /**
         * Puts a character in the world at ([x], [y]) and hands back its [NetId].
         *
         * Straight onto the world rather than through the barrier, because every caller is between
         * ticks: a session adds a client from its network pump, before it steps. `ClearingWriter`
         * and `ClearingReplicationTest` build entities the same way and say the same thing.
         *
         * The body is **dynamic**, and that is what makes a rock stop the character: a kinematic
         * body pushes what is in its way and is stopped by nothing, and a `Teleport` ignores
         * collision altogether. `Transform3D` places it (issue #247) and the solved pose comes back
         * into `Transform3D` after every step.
         */
        public fun spawn(
            world: World,
            netIds: NetIdIndex,
            x: Float,
            y: Float,
            owner: Int = UNOWNED,
        ): NetId {
            val entity = world.entity {
                it += Transform3D(x = x, y = y, scaleX = SCALE, scaleY = SCALE, scaleZ = SCALE)
                it += PhysicsBody(kind = BodyKind.Dynamic, x = x, y = y)
                it += Circle(RADIUS)
                it += Player(owner = owner)
                it += Animator()
            }
            return netIds.allocate(entity)
        }
    }
}

/**
 * Which [Intent] drives which character, for a process with more than one pair of hands in it.
 *
 * `IntentState` holds one `Intent` per world, because a single-player process has one player. A
 * server with two connections has two, and they are not interchangeable: the axis that arrived from
 * the second client must reach the second client's character and no other, or one player steers the
 * other's. `moba`'s `PlayerIntents` is the same seam for the same reason.
 *
 * A character no seat answers for gets `null`, and [PlayerControlSystem] zeroes its axis rather than
 * leaving it walking on the last thing its player asked for.
 */
public fun interface HollowIntents {

    /** This tick's input for the character [self], or `null` when nobody is driving it. */
    public fun intentFor(self: NetId): Intent?
}

/**
 * Turns this tick's [Intent] into every [Player]'s move axis.
 *
 * It reads a **value**, not a device: `IntentSampleSystem` filled `IntentState.intent` at the top of
 * this same tick out of whatever source is wired - a keyboard, an agent's `input.*` tools, a
 * command that arrived over the wire - and there is no branch here for which. That is what makes an
 * agent driving this game run the same code a human does.
 *
 * Registered at `SimPhase.Intent` after `IntentSampleSystem`, so the axis it reads was sampled on
 * this tick rather than the previous one.
 */
public class PlayerControlSystem(private val input: IntentState) : SimSystem() {

    /**
     * Per-character input, or `null` for the one-pair-of-hands case.
     *
     * A `var` set after the world is built rather than a constructor parameter, and forced rather
     * than chosen: [HollowModule] constructs this system out of a factory handed only the
     * `GameContext`, and the router belongs to a [dev.wildware.hollow.net.HollowServer] that does
     * not exist until after `definition.build()`. It is scoped to **this world's** instance, so two
     * sessions in one process route their own peers' hands to their own characters.
     */
    public var intents: HollowIntents? = null

    /** Resolved once at construction; a `world.family { }` per tick is a lookup on a hot path. */
    private val players: Family = world.family { all(Player) }

    private val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]

    /** Reused: clamping the axis on a per-tick path must not allocate. */
    private val axis = MoveAxis()

    override fun onTick() {
        val router = intents
        // Read once when there is one pair of hands, so single-player looks nothing up per entity.
        val shared = if (router == null) input.intent else null
        players.forEach { entity ->
            val player = entity[Player]
            val intent = shared ?: router?.intentFor(netIds.netIdOf(entity))
            if (intent == null) {
                player.moveX = 0f
                player.moveY = 0f
                player.running = false
                return@forEach
            }
            axis.set(intent.axisX(HollowControls.MOVE_AXIS), intent.axisY(HollowControls.MOVE_AXIS))
            player.moveX = axis.x
            player.moveY = axis.y
            player.running = intent.isPressed(HollowControls.RUN_ACTION)
        }
    }
}

/**
 * Gives every character the velocity its player asked for, once a tick, before the solver steps.
 *
 * ## Why a velocity and not a position
 *
 * Writing `Transform3D` would walk the character through the trees: `Transform3D` is an *output* for
 * a dynamic body (issue #247), and the solver would put it straight back. Writing the velocity is
 * what a Box2D character controller is - the solver sweeps the body a tick's worth, resolves
 * whatever it meets, and `Transform3DFromBodySystem` copies the result back. A tick spent against a
 * rock therefore moves the character less than the axis asked for, which is the whole of "the player
 * stops at a rock".
 *
 * The push itself is `udea-physics2d`'s `DrivenVelocitySystem`, which hands the solver any velocity
 * a game wrote onto a `PhysicsBody` since the last step.
 *
 * Registered at `SimPhase.Movement`, which is before `SimPhase.Physics` by definition - "the
 * authoritative movement model for anything predicted", which is exactly what this is.
 */
public class PlayerMovementSystem : SimSystem() {

    private val players: Family = world.family { all(Player, PhysicsBody) }

    override fun onTick() {
        players.forEach { entity ->
            val player = entity[Player]
            val body = entity[PhysicsBody]
            val speed = HollowMovement.speed(player.running)
            // Every tick, including the still ones: a character whose velocity was left alone would
            // coast on the last thing its player asked for, since there is no gravity or friction
            // holding it back on the ground plane.
            body.linearX = player.moveX * speed
            body.linearY = player.moveY * speed
        }
    }
}
