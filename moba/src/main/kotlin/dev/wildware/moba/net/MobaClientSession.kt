package dev.wildware.moba.net

import com.github.quillraven.fleks.World
import dev.wildware.moba.MobaGame
import dev.wildware.moba.Player
import dev.wildware.moba.PlayerReplicator
import dev.wildware.moba.Position
import dev.wildware.moba.PositionReplicator
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.GameUnitReplicator
import dev.wildware.moba.level.UnitKind
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.SnapshotTimeTravel
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.prediction.EntityInterpolator
import dev.wildware.udea.net.prediction.InputAckSource
import dev.wildware.udea.net.prediction.InterpolationClock
import dev.wildware.udea.net.prediction.LocalPrediction
import dev.wildware.udea.net.prediction.PlanarMoveModel
import dev.wildware.udea.net.prediction.PredictedPose
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.udea.net.wire.ReplicaStore
import dev.wildware.udea.net.wire.SnapshotApplySink

/**
 * A connected `moba` client: it sends input, predicts its own champion, and interpolates
 * everyone else's.
 *
 * ## What it holds
 *
 * A real [GameHost] over the identical [MobaGame.definition] the server runs, and therefore a
 * real Fleks world, the real `NetIdIndex`, and every renderer `MobaScene` registers. What it
 * does **not** hold is a copy of the level: the client seeds no scene at all. Every entity in
 * its world arrived over the wire and was put there by [ReplicaApplier]. That is the honest
 * demonstration - if replication stopped, the client's screen would be empty rather than
 * quietly running its own game.
 *
 * ## Why the client does not step the simulation
 *
 * `moba` has no authority gating anywhere in it: no system in the game asks
 * `GameContext.role.isAuthoritative`. Stepping the shipped module list on a client therefore
 * runs `UnitBattleSystem`, the ability execs, `RespawnSystem` **and** `MatchSystem` - and
 * `MatchSystem.restart` calls `ctx.scenes.requestScene`, which tears the world down and
 * repopulates it from the level on the client's own schedule. A client that rebuilt the level
 * out from under the state it was being sent is worse than one that renders a little less.
 *
 * So the loop here is receive, apply, **predict**, send. The world is still a replicated view;
 * what is new (issue #112) is that the view is no longer only "wherever the last packet put
 * everybody".
 *
 * ## Prediction, for the one unit a round trip is intolerable on
 *
 * At 150ms each way a player who waited for the server would see their own key press eighteen
 * ticks late, which is not a laggy game, it is a different one. So this client applies its own
 * command to its own champion **on the tick the command is minted** ([prediction]), and
 * reconciles against the server's later answer by replaying whatever the server has not yet
 * simulated. The step it replays through is [PlanarMoveModel], which is `PlayerMovementSystem`'s
 * rule as a pure function over the *dequantised* axis - so on a lossless link the replay is
 * bit-identical to the prediction and there is nothing to correct.
 *
 * Corrections are smoothed rather than snapped; that is `PredictionSmoothing`, and it is the
 * difference between a nudge and what players call rubber-banding.
 *
 * ## Interpolation, for everybody else
 *
 * Remote units are drawn at [renderClock]'s tick, which trails the newest received server tick
 * by an interpolation delay, so there is almost always a later snapshot to slide towards.
 * Without it a remote champion sits still and jumps once per arrival.
 *
 * ## What is honestly missing, and none of it is in this file
 *
 *  - **the client's `SimClock` never advances.** `MobaScene` computes an animation playhead as
 *    `clock.tick - CharacterView.startTick`, so poses still do not play on a client.
 *    [renderClock] is an interpolation clock, not the simulation's; `SimClock.moveTo` is
 *    `internal` to `udea-core` and would have to be opened up (or a client-side module list that
 *    omits the authoritative systems introduced) before animation follows.
 *  - ~~the acknowledged input sequence is not on the wire~~. **Fixed.**
 *    `PacketHeader.inputAck` carries `JitterBuffer.lastProcessedInputSeq`, written by
 *    `ReplicationServer.send` and read by `ReplicationClient.onPacket`, so [InputAckSource] is
 *    now a seam for a test rather than a stub: the default reads the real number off the wire.
 *  - **`state()` still hashes the replicated world, not the drawn one.** [presentView] writes
 *    the predicted and interpolated positions onto the live `Position` components, and defaults
 *    to off, because a presentation value in the hashed state would make the client disagree
 *    with the server by construction. A window turns it on; a proof does not.
 */
public class MobaClientSession(

    /** Which client this is, from the server's point of view. */
    public val peer: PeerId,

    /** Where datagrams go and come from. Any [Transport]. */
    private val transport: Transport,

    /**
     * The host this client renders through.
     *
     * Defaulted to a headless one so a proof or a test needs no GL context; `MobaClient` passes
     * the windowed host its LWJGL3 backend built, and nothing else about this class changes.
     */
    public val host: GameHost = MobaGame.host(RenderMode.Headless),

    /** Largest datagram this client will build. Must match the server's. */
    private val mtu: Int = LoopbackNetwork.DEFAULT_MTU,

    /**
     * Where "the server has simulated my command N" comes from.
     *
     * `null`, the default, means **read it off the wire**: `PacketHeader.inputAck` now carries
     * the last command the server simulated, so a session predicts without anything being
     * arranged for it. Pass [InputAckSource.NONE] to switch prediction off, or a table to drive
     * it from a proof. It is a constructor parameter and not a `var` so that a session either
     * predicts from the first packet or never does, rather than changing behaviour halfway
     * through a match.
     */
    private val inputAck: InputAckSource? = null,

    /**
     * Whether to write the predicted and interpolated view onto the live `Position` components.
     *
     * Off by default. On, this world stops being a faithful copy of the server's and becomes a
     * *presentation* of it - which is what a window wants and what a hash comparison must never
     * be handed. `MobaClient` is the caller that should turn it on.
     */
    public var presentView: Boolean = false,
) : AutoCloseable {

    private val travel: SnapshotTimeTravel = checkNotNull(
        host.game.simulation.travel as? SnapshotTimeTravel,
    ) {
        "the client reads its component registry off the definition's snapshot ring, and this " +
            "host was built without one"
    }

    /** The component types this client speaks. The same list the server built its protocol from. */
    public val registry: ComponentRegistry get() = travel.ring.registry

    /** This build's protocol. A server on another build is refused by name, not ignored. */
    public val protocol: ProtocolDescriptor = MobaNet.protocol(registry)

    /** The receiving half: applies deltas, acknowledges them, sends input. */
    public val replication: ReplicationClient =
        ReplicationClient(peer, registry, protocol, transport, mtu = mtu)

    private val netIds: NetIdIndex = host.ctx[CoreModule.NET_IDS]

    /** Moves what arrived off the wire onto live Fleks components. */
    public val applier: ReplicaApplier = ReplicaApplier(
        registry = registry,
        world = host.world,
        netIds = netIds,
        barrier = host.ctx.barrier,
        ctx = host.ctx,
    )

    /**
     * Captures this client's own world, for an agreement check against the server's.
     *
     * Built over the client's world with the same registry the server captures with, so the two
     * captures are directly comparable - see [NetStateProbe] for why the comparison is over the
     * `@Net` set rather than the whole capture.
     */
    private val capture: SnapshotService =
        SnapshotService(registry, host.world, host.ctx, netIds)

    /** Where remote units are drawn: the newest server tick, held an interpolation delay back. */
    public val renderClock: InterpolationClock = InterpolationClock()

    /** Per-remote-unit snapshot buffers. Fed every time a newer server tick lands. */
    public val interpolation: EntityInterpolator = EntityInterpolator()

    /**
     * This client's own champion, predicted. Null until the champion is identified, and always
     * null when no [InputAckSource] was supplied.
     */
    public var prediction: LocalPrediction? = null
        private set

    /** The champion this connection drives, or [NetId.NONE] until a packet names one. */
    public var champion: NetId = NetId.NONE
        private set

    /** The client's Fleks world. What the renderer draws. */
    public val world: World get() = host.world

    /** Newest server tick this client holds state for. */
    public val serverTick: Tick get() = replication.serverTick

    /** Packets applied. Zero means nothing has arrived yet, not that nothing changed. */
    public val applied: Long get() = replication.applied

    /** Packets discarded as older than, or a duplicate of, one already applied. */
    public val staleDropped: Long get() = replication.staleDropped

    /** Commands this client has produced. Also the next sequence number. */
    private var producedSeq: Int = 0

    /** Scratch for a sampled remote pose. One per session; sampling allocates nothing. */
    private val scratch: PredictedPose = PredictedPose()

    /** Raw [NetId]s the datagram being processed wrote, in the order the section named them. */
    private var touched: IntArray = IntArray(INITIAL_TOUCHED)
    private var touchedCount: Int = 0

    /** Whether the datagram being processed wrote this client's own champion. */
    private var championTouched: Boolean = false

    init {
        // Deduplicated against the previous id rather than through a set: a snapshot section
        // names each entity once and lists its components together, so equal ids are adjacent.
        replication.applySink = SnapshotApplySink { netId, _, _, _ ->
            if (touchedCount == 0 || touched[touchedCount - 1] != netId.raw) remember(netId)
            if (netId == champion) championTouched = true
        }
    }

    /**
     * The ack actually used. The wire-backed default ignores the tick it is asked about, because
     * [reconcileChampion] runs inside [onPacket] - so `replication.inputAck` is by construction
     * the ack of the very datagram being folded in, which is the number this wants.
     */
    private val ackSource: InputAckSource = inputAck ?: InputAckSource { replication.inputAck }

    private val positionComponent: Int = indexOf(Position::class.java)
    private val playerComponent: Int = indexOf(Player::class.java)
    private val unitComponent: Int = indexOf(GameUnit::class.java)

    /**
     * Feeds one received datagram in, and folds what it changed into the client's view.
     *
     * The folding happens **here** and not in [tick], and that is the whole of a defect worth
     * naming. `ReplicationServer` packs by priority against a per-entity baseline, so a client's
     * store is legitimately a *mix* of server ticks: the header says tick T while some entity in
     * it was last written at T-1. A client that walked the whole store once a tick and recorded
     * every entity at T would therefore stamp stale positions with fresh ticks - and a unit that
     * was deferred for three ticks and then updated would get three samples saying "did not move"
     * followed by one saying "moved four steps", which the interpolator then has to draw in a
     * single frame. That is a visible jump, produced by the client, on a link that dropped
     * nothing. The same mistake against the champion pairs a stale position with a fresh
     * acknowledgement and leaves the prediction permanently one command long.
     *
     * So only the entities this datagram actually wrote are folded, at this datagram's tick.
     * [SnapshotApplySink] is how `udea-net` says which those were.
     */
    public fun onPacket(buffer: ByteArray, offset: Int, length: Int) {
        touchedCount = 0
        championTouched = false
        if (replication.onPacket(buffer, offset, length)) ingest(replication.serverTick)
    }

    /**
     * Applies everything received, predicts this tick's input, and sends it.
     *
     * The order is the contract:
     *
     * 1. **apply**, so the ack that rides the outgoing packet names state this client holds;
     * 2. **advance the render clock**, so remote units are sampled at a tick that has moved;
     * 3. **predict**, so the champion moves on this tick rather than a round trip later;
     * 4. **send**, and only then bleed a tick's worth of the correction residual off.
     *
     * Reconciliation is deliberately not in this list: it happens in [onPacket], with the
     * datagram whose tick it belongs to, for the reason that method's KDoc gives.
     *
     * `ReplicationClient` will only send on an input tick (30Hz against a 60Hz simulation,
     * spec 3.3), and answers zero on the others.
     *
     * @return the bytes sent, or zero on a tick that sends nothing.
     */
    public fun tick(tick: Tick, command: MoveInput? = null): Int {
        applier.apply(replication.world)
        renderClock.advance()
        if (command != null) {
            prediction?.predict(command)
            replication.pushInput(command)
        }
        val sent = replication.sendTick(tick)
        prediction?.advance()
        if (presentView) present()
        return sent
    }

    /** Mints the next command for [tick] from an axis and a button field. */
    public fun command(
        tick: Tick,
        moveX: Float = 0f,
        moveY: Float = 0f,
        aim: Float = 0f,
        buttons: Int = 0,
    ): MoveInput {
        val seq = producedSeq
        producedSeq = (producedSeq + 1) and SEQ_MASK
        return MoveInput(seq, tick, moveX, moveY, aim, buttons)
    }

    /**
     * Where [netId] should be **drawn**: predicted for this client's champion, interpolated for
     * everybody else.
     *
     * @return false when nothing is known about [netId] yet, in which case [into] is untouched -
     *   drawing an entity at whatever the caller's pose happened to hold is worse than not
     *   drawing it.
     */
    public fun sampleView(netId: NetId, into: PredictedPose): Boolean {
        val local = prediction
        if (netId == champion && local != null && local.started) {
            into.set(local.x, local.y)
            return true
        }
        if (!renderClock.started) return false
        return interpolation.sample(netId, renderClock.renderTick, into)
    }

    /** This client's world, captured. Comparable with `MobaHostSession.stateAt`. */
    public fun state(): WorldSnapshot = capture.capture()

    /** How many `GameUnit`s this client can see. */
    public fun unitCount(): Int = NetStateProbe.unitCount(host.world)

    /** Appends [netId] to the touched list, growing it if a datagram carried more than it holds. */
    private fun remember(netId: NetId) {
        if (touchedCount == touched.size) touched = touched.copyOf(touched.size * 2)
        touched[touchedCount] = netId.raw
        touchedCount++
    }

    /**
     * Folds one datagram's writes into the prediction and the interpolation buffers.
     *
     * Only the entities it named, at the tick it named them for. See [onPacket].
     */
    private fun ingest(snapshotTick: Tick) {
        val store = replication.world
        if (champion == NetId.NONE) {
            champion = findChampion(store)
            // The datagram that creates the champion is also the one that says it is ours, and
            // the sink ran before that was known. Treat the create as a touch, or the first
            // reconciliation waits for the champion's next field change.
            if (champion != NetId.NONE) championTouched = true
        }
        val positions = store.storeAt(positionComponent)
        for (entry in 0 until touchedCount) {
            val netId = NetId.ofRaw(touched[entry])
            if (netId == champion) continue
            val row = store.rowOf(netId)
            if (row == ReplicaStore.ABSENT) continue
            val slot = store.slotOf(row, positionComponent)
            if (slot == ReplicaStore.ABSENT) continue
            interpolation.record(
                netId,
                snapshotTick,
                positions.getFloat(slot, PositionReplicator.FIELD_X),
                positions.getFloat(slot, PositionReplicator.FIELD_Y),
            )
        }
        if (championTouched) reconcileChampion(store, snapshotTick)
        // A `NetId` index is recycled the moment its entity dies, and `moba` recycles constantly:
        // an arrow's id becomes the next arrow's. A track that outlived its entity would hand the
        // next occupant a history it never had, and the slide between the two would cross the map.
        interpolation.forgetAllExcept { raw -> NetId.ofRaw(raw) in store }
        renderClock.onSnapshot(snapshotTick)
    }

    /** Reconciles the champion against the position this datagram carried for it. */
    private fun reconcileChampion(store: ReplicaStore, snapshotTick: Tick) {
        val row = store.rowOf(champion)
        if (row == ReplicaStore.ABSENT) return
        val slot = store.slotOf(row, positionComponent)
        if (slot == ReplicaStore.ABSENT) return
        val positions = store.storeAt(positionComponent)
        reconcile(
            store,
            row,
            positions.getFloat(slot, PositionReplicator.FIELD_X),
            positions.getFloat(slot, PositionReplicator.FIELD_Y),
            snapshotTick,
        )
    }

    /** Starts or corrects the local prediction from the server's answer for this tick. */
    private fun reconcile(store: ReplicaStore, row: Int, x: Float, y: Float, snapshotTick: Tick) {
        val local = prediction ?: startPrediction(store, row, x, y) ?: return
        local.reconcile(x, y, ackSource.ackAt(snapshotTick))
    }

    /**
     * Builds the predictor once the champion's position and speed are both known.
     *
     * Deferred to the first packet rather than done in the constructor because both facts arrive
     * over the wire: which entity this connection drives is `Player.owner`, and how fast it walks
     * is `GameUnit.kind`. A predictor built before either is known would predict the wrong unit
     * at the wrong speed from the origin.
     */
    private fun startPrediction(store: ReplicaStore, row: Int, x: Float, y: Float): LocalPrediction? {
        if (ackSource === InputAckSource.NONE) return null
        val built = LocalPrediction(PlanarMoveModel(speedOf(store, row)))
        built.start(x, y)
        prediction = built
        return built
    }

    /**
     * How fast the champion walks, from the replicated `GameUnit.kind`.
     *
     * Falls back to `PlayerMovementSystem`'s own fallback for a `Player` with no `GameUnit` -
     * which nothing in the level spawns, but a test may - so the two agree by construction rather
     * than by coincidence.
     */
    private fun speedOf(store: ReplicaStore, row: Int): Float {
        val slot = store.slotOf(row, unitComponent)
        if (slot == ReplicaStore.ABSENT) return FALLBACK_SPEED
        val kind = store.storeAt(unitComponent).getInt(slot, GameUnitReplicator.FIELD_KIND)
        return UnitKind.of(kind).moveSpeed
    }

    /**
     * The entity whose replicated `Player.owner` names this connection.
     *
     * `Player.owner` is the only thing on the wire that says "this one is yours"; a `NetId` is
     * allocation order, which a client cannot predict. Answers [NetId.NONE] while the champion's
     * create has not arrived, and [observe] simply asks again on the next tick.
     */
    private fun findChampion(store: ReplicaStore): NetId {
        val owners = store.storeAt(playerComponent)
        for (row in 0 until store.rowHighWater) {
            if (!store.isLive(row)) continue
            val slot = store.slotOf(row, playerComponent)
            if (slot == ReplicaStore.ABSENT) continue
            if (owners.getInt(slot, PlayerReplicator.FIELD_OWNER) == peer.raw) return store.netIdAt(row)
        }
        return NetId.NONE
    }

    /**
     * Writes the drawn view onto the live `Position` components. Only when [presentView].
     *
     * This is the step that makes prediction and interpolation *visible* rather than merely
     * computed, and it is also the step that stops this world being comparable with the
     * server's - which is why it is opt-in and why [state] is documented as an agreement capture
     * rather than a rendering one.
     */
    private fun present() {
        val store = replication.world
        for (row in 0 until store.rowHighWater) {
            if (!store.isLive(row)) continue
            val netId = store.netIdAt(row)
            if (!sampleView(netId, scratch)) continue
            val entity = netIds.resolveOrNull(netId) ?: continue
            with(host.world) {
                val position = entity.getOrNull(Position) ?: return@with
                position.x = scratch.x
                position.y = scratch.y
            }
        }
    }

    /** The registry index of [componentClass], resolved once. A per-tick scan would be a lookup. */
    private fun indexOf(componentClass: Class<*>): Int {
        for (index in 0 until registry.size) {
            if (registry.typeAt(index).componentClass.java == componentClass) return index
        }
        error("this session's registry has no ${componentClass.simpleName}")
    }

    override fun close() {
        host.stop()
        transport.close()
    }

    override fun toString(): String =
        "MobaClientSession($peer, serverTick=$serverTick, applied=$applied, champion=$champion, " +
            "$applier)"

    private companion object {

        /** 16-bit command sequence, matching `MoveInput`'s wire width. */
        const val SEQ_MASK: Int = 0xFFFF

        /** `PlayerMovementSystem.FALLBACK_SPEED`. @see speedOf */
        const val FALLBACK_SPEED: Float = 0.75f

        /** Entities one datagram is expected to write. Grown, never truncated. */
        const val INITIAL_TOUCHED: Int = 64
    }
}
