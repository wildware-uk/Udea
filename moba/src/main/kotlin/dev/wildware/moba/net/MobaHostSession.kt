package dev.wildware.moba.net

import dev.wildware.moba.MobaControls
import dev.wildware.moba.MobaGame
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotRing
import dev.wildware.udea.core.snapshot.SnapshotTimeTravel
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.replication.ReplicationServer
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.moba.ability.AbilityRpc
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilityActivation
import dev.wildware.udea.gas.ActivationResult
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GasServices
import dev.wildware.moba.ability.ChampionOwnership
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.rpc.RpcRateLimiter
import dev.wildware.udea.net.rpc.RpcRefusal
import dev.wildware.udea.net.rpc.RpcRegistry
import dev.wildware.udea.net.rpc.RpcServer
import dev.wildware.udea.net.wire.FrameReader
import dev.wildware.udea.net.wire.MessageType
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.render.input.IntentState

/**
 * `moba` as an **authoritative server**: it owns the simulation and replicates it to clients.
 *
 * This is the sentence `MobaServer`'s KDoc used to have to retract. The host underneath is the
 * ordinary [MobaGame.host] every other entry point builds - identical modules, identical scene,
 * identical [dev.wildware.udea.core.loop.GameLoop] - and everything network about it is the
 * three lines of [tick]: consume what clients sent, run the tick, replicate what happened.
 *
 * ## The ring is the baseline store, and there is not a second one
 *
 * [ReplicationServer] is handed [ring], which is the *same* [SnapshotRing] that backs
 * `time.rewind` on this host - reached through the definition's own [SnapshotTimeTravel], never
 * rebuilt. Spec 3.1 requires exactly that, and it is not a saving: a per-client shadow copy of
 * the world is what makes a server's memory scale with players instead of with entities, and it
 * is a second structure that can disagree with the first.
 *
 * A capture is forced every tick rather than left to `EngineConfig.snapshotIntervalTicks`,
 * because a baseline a client acked has to still be *in* the ring when the next delta is written
 * against it. The cadence's own captures are idempotent per tick, so this does not double up;
 * what it costs is a denser dense window, which is 120 ticks either way.
 *
 * ## Clients send input. Only input.
 *
 * A client's datagram reaches [ReplicationServer.onPacket] and nowhere else, and that method has
 * exactly two branches: acks, and `@InputCommand` frames into a per-client
 * [dev.wildware.udea.net.input.JitterBuffer]. There is no path from a received datagram to a
 * replicated component field. That is what makes "a client cannot own its own position"
 * structural rather than a rule; the old stack's `PacketUtil.kt:148` carried
 * `// TODO validate the sender!` because in that design there was something to validate.
 *
 * The consumed command becomes an [IntentSource] - the *same* seam a keyboard and an agent's
 * `input.*` tools are wired through - so a remote player drives `PlayerControlSystem` down the
 * identical code path a local one does.
 *
 * ## Ownership, and what is honestly missing
 *
 * The level contains exactly one `Player` entity ([MobaEntry.playerId] refuses a world with any
 * other number), so the **first** client to join drives it and later clients are spectators:
 * their input is consumed and discarded, and they receive the full world like anyone else.
 * Handing every client its own unit needs a per-entity owner binding and a per-owner
 * `IntentState`, which is a wave of its own.
 */
public class MobaHostSession(

    /** Where datagrams go. Any [Transport]: loopback, simulated, or a socket. */
    private val transport: Transport,

    /** Payload ceiling per datagram. */
    budget: BandwidthBudget = BandwidthBudget(),

    /**
     * Largest datagram this session will build, in bytes.
     *
     * Defaulted to the network's own 1200, which is what a real link carries. It is a parameter
     * because the agreement proof raises it: at 1200 the packer defers entities that do not fit
     * and a client's world is legitimately a *mix* of server ticks, which is correct replication
     * and not a state two peers can be hashed against each other on.
     */
    mtu: Int = LoopbackNetwork.DEFAULT_MTU,
) : AutoCloseable {

    /** The authoritative simulation. Headless: a dedicated server draws nothing. */
    public val host: GameHost = MobaGame.host(RenderMode.Headless)

    /** The player unit the first client drives. Resolved by seeding the level. */
    public val playerId: NetId = MobaEntry.seed(host)

    private val travel: SnapshotTimeTravel = checkNotNull(
        host.game.simulation.travel as? SnapshotTimeTravel,
    ) {
        "a replicating server needs a snapshot ring: the ring is the baseline store (spec 3.1), " +
            "and MobaGame.definition supplies one through `snapshotTimeTravel`"
    }

    /** The one ring. Rewind reads these slots; so does every client's delta baseline. */
    public val ring: SnapshotRing get() = travel.ring

    /** The component types this session speaks. Taken from the ring, so there is one list. */
    public val registry: ComponentRegistry get() = ring.registry

    /** This build's protocol. A client on another build is refused by name. */
    public val protocol: ProtocolDescriptor = MobaNet.protocol(registry)

    private val input = NetIntentSource()

    /** The packer. Handed the ring, never a copy of it. */
    public val replication: ReplicationServer = ReplicationServer(
        registry = registry,
        protocol = protocol,
        transport = transport,
        ring = ring,
        budget = budget,
        mtu = mtu,
    )

    /** Peers in join order. The first one drives [playerId]. */
    private val joined = ArrayList<PeerId>()

    /**
     * Which connection owns which champion. The generated ownership guard reads this and nothing
     * else, and every id it has not been told about answers [PeerId.SERVER] - which is every AI
     * unit on the field, every arrow in flight, and every id that does not exist.
     */
    public val ownership: ChampionOwnership = ChampionOwnership()

    /** Every RPC this build speaks. A client shares it, so the wire indices agree. */
    public val rpcRegistry: RpcRegistry = AbilityRpc.registry()

    /**
     * The half of the session that refuses a client's RPC, and the reason this class parses a
     * datagram before handing it on.
     *
     * `ReplicationServer.onPacket` deliberately understands exactly two things - acks and input -
     * and drops everything else on the floor, which is what makes "a client cannot write a
     * component" structural. So an `@Rpc` frame has to be picked out here, ahead of it. The cost
     * is that the fixed header is parsed twice per datagram; the alternative is a third branch
     * inside `udea-net`'s receive path, and a third branch there is exactly where the old stack's
     * `PacketUtil.kt:148` `// TODO validate the sender!` used to live.
     */
    public val rpc: RpcServer = RpcServer(
        rpcRegistry,
        ownership,
        RpcRateLimiter(ticksPerSecond = TICKS_PER_SECOND, rpcCount = rpcRegistry.size),
    )

    /** The most recent refusal, for a log line or an assertion. Null while nothing was refused. */
    public var lastRefusal: RpcRefusal? = null
        private set

    init {
        // The remote player's hands. Installed the way `MobaAgent` installs an `InjectedIntent`
        // and `MobaClient` installs a keyboard: the simulation cannot tell the three apart.
        host.ctx[IntentState.KEY].source = input
        // Accepted activations reach this world's ability path and no other. `AbilityRpc.sink` is
        // process state - the honest cost of a top-level `@Rpc` body, stated in its own KDoc - so
        // a second session in one JVM rebinds it, which is why `close` puts it back.
        AbilityRpc.bind { self, slot -> activate(self, slot) }
    }

    /** The tick the server is about to run. */
    public val tick: Tick get() = host.tick

    /** Registers [peer]. Idempotent. The first caller gets the player unit, and owns it. */
    public fun addClient(peer: PeerId) {
        if (joined.contains(peer)) return
        replication.addClient(peer)
        joined += peer
        // The first joiner drives the level's one `Player`, so it is the one connection that may
        // fire that champion's abilities. Every later client owns nothing at all, which is what
        // makes a spectator's `activateAbility` a typed refusal rather than a silent no-op.
        if (joined.size == 1) ownership.assign(playerId, peer)
    }

    /**
     * Drops [peer]: it stops being replicated to, and its baselines are forgotten.
     *
     * Needed the moment the transport is a socket rather than an in-process link. A disconnected
     * client that is still registered is packed and sent a datagram every tick for the life of
     * the process; worse, `UdpTransport` recycles peer slots, so the *next* connection into that
     * slot would inherit the dead client's acked baseline ticks and be delta-encoded against
     * state belonging to somebody who has left. That decodes cleanly and is wrong in every field.
     *
     * If the controlling peer leaves, the next joiner in order inherits [playerId] - which falls
     * out of [controllingPeer] reading the head of the list, not out of a branch here.
     *
     * @return true when [peer] was joined.
     */
    public fun removeClient(peer: PeerId): Boolean {
        if (!joined.remove(peer)) return false
        replication.removeClient(peer)
        ownership.release(peer)
        // The head of the list is the controller, so a departure promotes the next joiner - and
        // ownership has to follow it, or the new controller's own activations would be refused.
        joined.firstOrNull()?.let { ownership.assign(playerId, it) }
        return true
    }

    /** Every joined peer, in join order. */
    public fun clients(): List<PeerId> = joined.toList()

    /** The peer that drives [playerId], or null while nobody has joined. */
    public fun controllingPeer(): PeerId? = joined.firstOrNull()

    /**
     * Feeds one received datagram in: RPCs through the ownership guard, then acks and input.
     *
     * There is still no branch below this that can write a replicated component field out of a
     * client's bytes. What an `@Rpc` frame may do is ask for an activation on an entity the
     * sender **owns**, and the generated `receive` compares the sender against [ownership] before
     * the body is reached. That comparison is the one the old engine did not have.
     *
     * @return the refusal, when the datagram carried an RPC that was refused. Null otherwise.
     */
    public fun onPacket(from: PeerId, buffer: ByteArray, offset: Int, length: Int): RpcRefusal? {
        val refusal = dispatchRpcs(from, buffer, offset, length)
        replication.onPacket(from, buffer, offset, length)
        return refusal
    }

    /**
     * Walks the datagram's frames for [MessageType.Rpc] and hands each to [rpc].
     *
     * Malformed bytes are dropped rather than thrown. A datagram is attacker-controlled, and a
     * parse failure that propagated would let one hostile packet end the server's tick - which is
     * a denial of service dressed as a stack trace.
     */
    private fun dispatchRpcs(from: PeerId, buffer: ByteArray, offset: Int, length: Int): RpcRefusal? {
        var refusal: RpcRefusal? = null
        try {
            val src = BitBufferReader(buffer, offset, length)
            val header = PacketHeader.read(src)
            if (header.protoHash != protocol.protoHash) return null
            val walker = FrameReader(buffer, offset, length, src.bitPosition)
            while (true) {
                val frame = walker.next() ?: break
                if (frame.type != MessageType.Rpc) continue
                val answer = rpc.receive(from, walker.readerFor(frame), host.tick)
                if (answer != null) {
                    refusal = answer
                    lastRefusal = answer
                }
            }
        } catch (malformed: RuntimeException) {
            return refusal
        }
        return refusal
    }

    /**
     * Runs an accepted activation against this host's world. Reached only through [rpc].
     *
     * The identical call `PlayerControlSystem` makes for a local key press and `UnitBrain` makes
     * for an AI unit: same `AbilityActivation`, same cost check, same cooldown, same tick. A
     * remote player gets no separate path into the ability system, so there is no second place a
     * permission check could have been forgotten.
     */
    private fun activate(self: NetId, slot: Int): ActivationResult {
        val gas = host.ctx[GasServices.KEY]
        val entity = host.ctx[CoreModule.NET_IDS].resolveOrNull(self)
            ?: return ActivationResult.NoAuthority
        return with(host.world) {
            val abilities = entity.getOrNull(Abilities) ?: return@with ActivationResult.NoAuthority
            val attributes = entity.getOrNull(Attributes) ?: return@with ActivationResult.NoAuthority
            val effects = entity.getOrNull(GameplayEffects) ?: return@with ActivationResult.NoAuthority
            gas.activation.activate(self, abilities, attributes, effects, slot, host.tick)
        }
    }

    /**
     * One authoritative tick: consume input, simulate, capture, replicate.
     *
     * The order is the whole contract. Input is drained **before** the step so that the command
     * a client sent for this tick is the one `SimPhase.Intent` samples; the capture is **after**
     * it, so what goes on the wire is the world as it ended the tick; and the broadcast reads the
     * slot the capture just committed, so every client's delta is written against a baseline that
     * provably exists in the ring.
     */
    public fun tick() {
        drainInput()
        host.run(1)
        travel.captureNow()
        replication.broadcast(state())
    }

    /** The captured world at the server's current tick. */
    public fun state(): WorldSnapshot = stateAt(host.tick)

    /**
     * The captured world at [at] exactly.
     *
     * Exactly, and not "nearest at or before": an agreement check against the newest capture
     * would be asserting that replication is instantaneous rather than that it is correct. A
     * client can only ever hold a tick the server has already left.
     */
    public fun stateAt(at: Tick): WorldSnapshot {
        val slot = ring.nearestAtOrBefore(at)
            ?: error("the server's ring holds nothing at or before $at")
        check(slot.tick == at) { "the ring no longer holds $at; the nearest slot is ${slot.tick}" }
        return slot
    }

    /**
     * Consumes exactly one command per client per tick and hands the owner's to the simulation.
     *
     * Every client's buffer is consumed, including a spectator's, because a jitter buffer that is
     * filled and never drained is a buffer that overflows and reports a client as flooding.
     */
    private fun drainInput() {
        val owner = controllingPeer()
        for (peer in joined) {
            val command = replication.jitterOf(peer).consume(host.tick)
            if (peer == owner) input.pending = command
        }
    }

    override fun close() {
        AbilityRpc.unbind()
        host.stop()
        transport.close()
    }

    override fun toString(): String =
        "MobaHostSession(tick=${host.tick}, clients=${joined.size}, player=$playerId)"

    private companion object {

        /** The rate the RPC limiter denominates its token buckets in. The simulation's own. */
        const val TICKS_PER_SECOND = 60
    }
}

/**
 * A [MoveInput] presented to the simulation as an ordinary [IntentSource].
 *
 * Nothing downstream knows the axis came off a socket. `IntentSampleSystem` calls [sample] at the
 * top of `SimPhase.Intent` exactly as it does for `DeviceIntent` and `InjectedIntent`, and
 * `PlayerControlSystem` reads the result - so the remote player's swing goes through the same
 * `AbilityActivation` call, with the same cost check and the same cooldown, as the AI soldier
 * standing next to them.
 *
 * A null [pending] samples nothing, which leaves the intent idle: that is what a client whose
 * packets have not arrived yet should look like, and it is what the jitter buffer's own
 * starvation repeat exists to make rare.
 */
public class NetIntentSource : IntentSource {

    /** The command this tick. Set by the session immediately before the step. */
    public var pending: MoveInput? = null

    override fun sample(into: Intent) {
        val command = pending ?: return
        into.setAxis(MobaControls.MOVE_AXIS, command.moveX, command.moveY)
        if (command.buttons and PRIMARY != 0) into.setPressCount(MobaControls.ATTACK_ACTION, 1)
        if (command.buttons and SECONDARY != 0) into.setPressCount(MobaControls.ATTACK_2_ACTION, 1)
    }

    public companion object {

        /** Button bit 0: the basic attack, `PlayerControlSystem.SLOT_PRIMARY`. */
        public const val PRIMARY: Int = 1

        /** Button bit 1: the special, `PlayerControlSystem.SLOT_SECONDARY`. */
        public const val SECONDARY: Int = 2
    }
}
