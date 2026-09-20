package dev.wildware.hollow.net

import dev.wildware.hollow.HollowGame
import dev.wildware.hollow.HollowHost
import dev.wildware.hollow.HollowMoveModel
import dev.wildware.hollow.HollowMovement
import dev.wildware.hollow.Player
import dev.wildware.hollow.PlayerReplicator
import dev.wildware.udea.core.NetRole
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotTimeTravel
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.core.spatial.Transform3DReplicator
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.prediction.LocalPrediction
import dev.wildware.udea.net.prediction.PredictedPose
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.udea.net.wire.ReplicaStore
import kotlin.reflect.KClass

/**
 * One Hollow client: the level loaded locally, the server's world replicated onto it over
 * [transport] (issue #249), and its own character predicted so that a key press moves it on the tick
 * it was pressed (issue #250).
 *
 * The client loads the static clearing itself - see [HollowNet] - and from then on
 * [HollowReplicaApplier] makes the world agree with what the server sends. Everything the server
 * spawns at run time, the characters included, reaches this world through replication alone.
 *
 * ## Prediction, for the one character a round trip is intolerable on
 *
 * At 150ms each way a player who waited for the server would see their own key press eighteen ticks
 * late, which is not a laggy game but a different one. So [tick] applies this client's command to
 * its own character on the tick the command is minted, and reconciles against the server's later
 * answer by replaying whatever the server has not yet simulated. The rule it replays through is
 * [HollowMoveModel], which is the server's own [dev.wildware.hollow.PlayerMovementSystem] arithmetic
 * as a pure function over the dequantised axis.
 *
 * What the predictor does **not** have is Box2D. A tick the character spends against a rock moves it
 * less than the model says, so the server's answer disagrees and `PredictionSmoothing` absorbs the
 * difference over about a fifth of a second. That is prediction working rather than prediction
 * failing: the server is the authority on where a character ended up, always, and the disagreement
 * is the information.
 *
 * [predictedPose] is where the local character should be **drawn**. It is deliberately not written
 * onto the world's `Transform3D`: this world is a faithful copy of the server's, and a presentation
 * value written into it would make an agreement check compare a prediction against an authority.
 * The window reads it; a proof does not.
 *
 * @param host the client's game. Headless for a test; the window's for a player.
 */
public class HollowClient(
    public val peer: PeerId,
    private val transport: Transport,
    private val opened: HollowHost = HollowGame.build(RenderMode.Headless, role = NetRole.Client),
    mtu: Int = LoopbackNetwork.DEFAULT_MTU,
) : AutoCloseable {

    /** This client's game. */
    public val host: GameHost get() = opened.host

    init {
        HollowGame.seed(host)
    }

    private val travel: SnapshotTimeTravel = checkNotNull(host.game.simulation.travel as? SnapshotTimeTravel) {
        "the client reads its component registry off the definition's snapshot ring, and this host has none"
    }

    /** What replicates: the same registry the server built, from the same sources. */
    internal val registry: ComponentRegistry get() = travel.ring.registry

    /** This build's protocol. A server whose protocol differs is refused at connect. */
    public val protocol: ProtocolDescriptor = HollowNet.protocol(registry)

    /** The replication half: the replica store, acknowledgements and the wire. */
    public val replication: ReplicationClient = ReplicationClient(peer, registry, protocol, transport, mtu = mtu)

    /** What puts the replica store onto the world. */
    public val applier: HollowReplicaApplier = HollowReplicaApplier(
        registry = registry,
        world = host.world,
        netIds = host.ctx[CoreModule.NET_IDS],
        barrier = host.ctx.barrier,
        ctx = host.ctx,
    )

    /** The character this connection drives, or [NetId.NONE] until a packet names one. */
    public var character: NetId = NetId.NONE
        private set

    /** This client's own character, predicted. Null until the character has been identified. */
    public var prediction: LocalPrediction? = null
        private set

    /** Where the local character should be drawn: the predicted pose, correction residual included. */
    public val predictedPose: PredictedPose = PredictedPose()

    /** The newest server tick this client has a snapshot of. */
    public val serverTick: Tick get() = replication.serverTick

    /** Commands this client has produced. Also the next sequence number. */
    private var producedSeq: Int = 0

    private val playerComponent: Int = indexOf(Player::class)
    private val transformComponent: Int = indexOf(Transform3D::class)

    /** A datagram from the server. Reconciles the prediction against what it carried. */
    public fun onPacket(buffer: ByteArray, offset: Int, length: Int) {
        if (!replication.onPacket(buffer, offset, length)) return
        val store = replication.world
        if (character == NetId.NONE) character = findCharacter(store)
        reconcile(store)
    }

    /**
     * Applies what has arrived to the world, predicts [command], and sends this tick's
     * acknowledgement.
     *
     * The order is the contract: apply first, so the acknowledgement that rides the outgoing packet
     * names state this client holds; predict next, so the character answers on this tick rather than
     * a round trip later; send last, and only then bleed a tick's worth of correction residual off.
     *
     * @return datagrams sent. `ReplicationClient` sends on an input tick alone - 30Hz against a 60Hz
     *   simulation, spec 3.3 - and answers zero on the others.
     */
    public fun tick(tick: Tick, command: MoveInput? = null): Int {
        applier.apply(replication.world)
        if (command != null) {
            prediction?.predict(command)
            replication.pushInput(command)
        }
        val sent = replication.sendTick(tick)
        prediction?.advance()
        prediction?.let { predictedPose.set(it.x, it.y) }
        return sent
    }

    /** Mints the next command for [tick] from a world-space move axis and the run control. */
    public fun command(
        tick: Tick,
        moveX: Float = 0f,
        moveY: Float = 0f,
        running: Boolean = false,
        aim: Float = 0f,
    ): MoveInput {
        val seq = producedSeq
        producedSeq = (producedSeq + 1) and SEQ_MASK
        val buttons = if (running) HollowMovement.RUN_BUTTON else 0
        return MoveInput(seq, tick, moveX, moveY, aim, buttons)
    }

    override fun close() {
        opened.close()
        transport.close()
    }

    override fun toString(): String =
        "HollowClient($peer, serverTick=$serverTick, character=$character, $applier)"

    /**
     * Starts or corrects the prediction from the position this datagram carried for the character.
     *
     * The ack is `replication.inputAck`, which `PacketHeader.inputAck` carries: the last command the
     * server actually **simulated**, which is not the same as the last packet it received. The
     * jitter buffer sits between the two by design, and reconciling against a packet ack would
     * replay a jitter buffer's worth of commands too many, every tick, in a fixed direction.
     */
    private fun reconcile(store: ReplicaStore) {
        val id = character
        if (id == NetId.NONE) return
        val row = store.rowOf(id)
        if (row == ReplicaStore.ABSENT) return
        val slot = store.slotOf(row, transformComponent)
        if (slot == ReplicaStore.ABSENT) return
        val transforms = store.storeAt(transformComponent)
        val x = transforms.getFloat(slot, Transform3DReplicator.FIELD_X)
        val y = transforms.getFloat(slot, Transform3DReplicator.FIELD_Y)
        val local = prediction ?: start(x, y)
        local.reconcile(x, y, replication.inputAck)
        predictedPose.set(local.x, local.y)
    }

    /** Builds the predictor once the server has said where this client's character is. */
    private fun start(x: Float, y: Float): LocalPrediction {
        val built = LocalPrediction(HollowMoveModel())
        built.start(x, y)
        prediction = built
        return built
    }

    /**
     * The entity whose replicated `Player.owner` names this connection.
     *
     * `Player.owner` is the only thing on the wire that says "this one is yours"; a `NetId` is
     * allocation order, which a client cannot predict. Answers [NetId.NONE] while the character's
     * create has not arrived, and the next datagram simply asks again.
     */
    private fun findCharacter(store: ReplicaStore): NetId {
        val owners = store.storeAt(playerComponent)
        for (row in 0 until store.rowHighWater) {
            if (!store.isLive(row)) continue
            val slot = store.slotOf(row, playerComponent)
            if (slot == ReplicaStore.ABSENT) continue
            if (owners.getInt(slot, PlayerReplicator.FIELD_OWNER) == peer.raw) return store.netIdAt(row)
        }
        return NetId.NONE
    }

    /** The registry index of [componentClass], resolved once. A per-packet scan would be a lookup. */
    private fun indexOf(componentClass: KClass<*>): Int {
        for (index in 0 until registry.size) {
            if (registry.typeAt(index).componentClass == componentClass) return index
        }
        error("this session's registry has no $componentClass")
    }

    private companion object {
        /** 16-bit command sequence, matching `MoveInput`'s wire width. */
        const val SEQ_MASK: Int = 0xFFFF
    }
}
