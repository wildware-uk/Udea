package dev.wildware.udea.agent.host.net

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.relevancy.FogOfWar
import dev.wildware.udea.net.relevancy.VisionGrid
import dev.wildware.udea.net.replication.Desync
import dev.wildware.udea.net.replication.DesyncReport
import dev.wildware.udea.net.replication.RelevancySet
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.replication.ReplicationServer
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.NetEndpoint
import dev.wildware.udea.net.transport.NetHarness
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.TransportStats
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.udea.net.wire.ReplicaStore

/**
 * The input a client is *holding*, as a player holds a key.
 *
 * A held axis rather than a one-shot command, and that is not a convenience. A client samples
 * its input every tick and every packet carries the last three samples, so the server's
 * `JitterBuffer` fills, reaches its target depth and starts consuming. A tool that queued a
 * single command would put exactly one entry in that buffer, the buffer would correctly refuse
 * to start draining below its target depth, and `net.input` would silently do nothing - a tool
 * that answers `ok` and moves nothing being the worst outcome available.
 */
private class HeldInput {
    var moveX: Float = 0f
    var moveY: Float = 0f
    var aim: Float = 0f
    var buttons: Int = 0
    var sent: Long = 0L
    var applied: Long = 0L
}

/** What one peer looks like right now, as [NetSession] reports it. */
public class PeerSnapshot(
    /** `server`, or `client1`, `client2`... */
    public val peer: String,
    /** The tick this reading is of: the arena's for the server, the last applied for a client. */
    public val tick: Tick,
    /** The avatar's x, or null when this peer holds no row for it. */
    public val x: Float?,
    /** The avatar's y, or null when this peer holds no row for it. */
    public val y: Float?,
    /** Which client's input owns the avatar. */
    public val owner: Int?,
    /** Input commands this peer has put on the wire. Zero on the server, which sends none. */
    public val inputsSent: Long,
    /** Input commands the *server* consumed for this peer and applied. */
    public val inputsApplied: Long,
    /** Live traffic counters for this peer's link to the other end. */
    public val stats: TransportStats,
) {
    override fun toString(): String = "PeerSnapshot($peer at ${tick.value}: $x, $y)"
}

/**
 * A server and n clients as **one addressable thing**: `net.spawn_session(clients = 2)`.
 *
 * ## The problem it exists for
 *
 * An agent debugging a desync has to send input on a client and assert authoritative state on
 * the server, and until now that meant two processes, two ports and two tool manifests, with
 * nothing tying them together and no way to make the link misbehave on purpose. Here it is one
 * handle with addressable peers: `client(1)` and `server` are arguments, not ports.
 *
 * ## Nothing here is a stand-in
 *
 * The wire is `:udea-net`'s: [ReplicationServer] over the real [dev.wildware.udea.core.snapshot.SnapshotRing],
 * [ReplicationClient] over the real `ReplicaStore`, framed by the real self-describing packet
 * format, carried by [dev.wildware.udea.net.transport.SimulatedTransport] whose every latency,
 * jitter, loss, reorder and duplication draw comes from a `SimRandom` seeded from [seed]. A
 * failure at 10% loss is therefore reproducible from its seed rather than a thing that happened
 * once. The one thing this is not is a socket: everything runs in this JVM, on the calling
 * thread, against a `ManualClock`. Nothing sleeps and nothing starts a thread.
 *
 * ## Clients send input; the server owns the position
 *
 * [hold] sets what a client is holding. Each client tick samples that into a [MoveInput] and
 * sends it; the server consumes one command per tick from that client's `JitterBuffer` and is
 * the only thing that ever writes an avatar's coordinates. There is no method here, and no path
 * through `ReplicationServer.onPacket`, by which a client could set its own position - which is
 * what `PacketUtil.kt:148`'s `// TODO validate the sender!` failed to do by intention.
 */
public class NetSession(
    /** How many clients to stand up. */
    public val clients: Int,
    /** The root seed. Every link and the arena derive from it. */
    public val seed: Long,
    /** The conditions every link starts on. */
    initialConditions: NetConditions = NetConditions.PERFECT,

    /**
     * Sight radius of every avatar, in world units, or zero for no fog at all.
     *
     * Zero by default, and that default is load-bearing rather than timid: every existing
     * `net.*` session, every desync hunt and the two-process UDP proof all assume each client is
     * told about every entity, and silently turning that off would make the transport tools lie.
     * A session asks for fog explicitly, gets [FogOfWar] as its `RelevancySet`, and the clients
     * are split alternately across two teams so there is something to be hidden from.
     */
    public val visionRadius: Float = 0f,
) {

    init {
        require(clients in 1..MAX_CLIENTS) {
            "a session stands up 1..$MAX_CLIENTS clients, was $clients; more than that is not " +
                "a debugging session, it is a load test"
        }
    }

    /** The authoritative world. */
    public val arena: NetArena = NetArena(seed)

    /** The links, the clock and the packet log. */
    public val harness: NetHarness = NetHarness(clients, seed, initialConditions = initialConditions)

    /** This build's protocol, shared by both ends. */
    public val protocol: ProtocolDescriptor = ProtocolDescriptor.of(arena.registry)

    /**
     * Per-team fog, or null when [visionRadius] is zero.
     *
     * The grid is centred on the arena's origin and sized for the whole area an avatar can reach
     * in a ten-second step, so nothing this session can do walks off it - and a body that somehow
     * did would clamp into an edge cell rather than vanish.
     */
    public val fog: FogOfWar? = if (visionRadius <= 0f) {
        null
    } else {
        FogOfWar(
            grid = VisionGrid(
                originX = -FOG_EXTENT,
                originY = -FOG_EXTENT,
                cellSize = FOG_CELL,
                columns = FOG_CELLS,
                rows = FOG_CELLS,
            ),
            teams = FOG_TEAMS,
        )
    }

    /** The authority. */
    public val server: ReplicationServer = ReplicationServer(
        registry = arena.registry,
        protocol = protocol,
        transport = harness.transport(PeerId.SERVER),
        ring = arena.ring,
        relevancy = fog ?: RelevancySet.ALL_VISIBLE,
    )

    private val held = Array(clients + 1) { HeldInput() }

    private val avatars = arrayOfNulls<NetId>(clients + 1)

    /**
     * How far apart the avatars spawn.
     *
     * Three sight radii, so a fogged session starts with every client out of every other client's
     * vision and `net.assert_not_visible` has something true to assert from tick one. Zero without
     * fog, which is the arrangement every pre-existing test was written against.
     */
    private val spawnSpacing: Float = if (fog == null) 0f else visionRadius * SPAWN_SPACING_RADII

    /** The client ends, `client(1)` first. */
    public val ends: List<ReplicationClient> = (1..clients).map { index ->
        val peer = PeerId.client(index)
        server.addClient(peer)
        fog?.assign(peer, teamOf(index))
        // Spread the avatars apart, so fog has something to hide. With no fog this is the old
        // shared origin plus a per-client offset, which no existing assertion depends on.
        avatars[index] = arena.spawn(owner = index, x = index * spawnSpacing, y = 0f)
        ReplicationClient(peer, arena.registry, protocol, harness.transport(peer))
    }

    /** The index of `NetAvatar` in the shared registry. Resolved once; it never moves. */
    private val avatarComponent: Int = arena.registry.indexOf(NetAvatarReplicator.typeId)

    /** Which team client [client] is on: clients alternate, so two clients are already opponents. */
    public fun teamOf(client: Int): Int = (client - 1) % FOG_TEAMS

    /**
     * Feeds this tick's positions to the fog solve. A no-op when the session has no fog.
     *
     * Called immediately before the broadcast and immediately after the capture, so the vision
     * the packer filters against is vision of the state it is about to send - a solve run against
     * last tick's positions would hide a body that had just stepped into the light.
     */
    private fun solveFog() {
        val live = fog ?: return
        live.beginSolve(arena.tick)
        for (index in 1..clients) {
            val netId = avatars[index] ?: continue
            val avatar = arena.avatar(netId)
            live.observe(netId, avatar.x, avatar.y, teamOf(index), visionRadius)
        }
        live.endSolve()
    }

    init {
        harness.register(ServerEndpoint())
        for (end in ends) harness.register(ClientEndpoint(end))
    }

    /** The tick the session has reached. */
    public val tick: Tick get() = harness.clock.tick

    /** Sets what [client] is holding. Takes effect on that client's next sampled tick. */
    public fun hold(client: Int, moveX: Float, moveY: Float, aim: Float, buttons: Int) {
        val input = held[checkedClient(client)]
        input.moveX = moveX
        input.moveY = moveY
        input.aim = aim
        input.buttons = buttons
    }

    /** Runs [ticks] ticks of the whole session. */
    public fun step(ticks: Int): Tick {
        require(ticks in 0..MAX_STEP) {
            "a single step runs 0..$MAX_STEP ticks, was $ticks; the call runs to completion " +
                "inside one barrier drain, so a larger number is a stalled simulation"
        }
        return harness.step(ticks)
    }

    /**
     * Reshapes one link, in milliseconds.
     *
     * The transport is denominated in **ticks**, on purpose, so a condition set means the same
     * thing on a fast machine and a slow one. Agents and issue trackers speak milliseconds, so
     * the conversion happens exactly here, against the arena's own tick rate, and the resolved
     * tick counts are handed back so a caller can see what 200ms became.
     */
    public fun conditions(client: Int?, latencyMs: Int, jitterMs: Int, loss: Float): NetConditions {
        require(latencyMs >= 0) { "latencyMs must be >= 0, was $latencyMs" }
        require(jitterMs >= 0) { "jitterMs must be >= 0, was $jitterMs" }
        require(loss in 0f..1f) { "loss is a probability in 0..1, was $loss" }
        val conditions = NetConditions(
            latencyTicks = ticksOf(latencyMs),
            jitterTicks = ticksOf(jitterMs),
            lossChance = loss,
        )
        if (client == null) {
            harness.setConditionsForAll(conditions)
        } else {
            harness.setConditions(PeerId.client(checkedClient(client)), conditions)
        }
        return conditions
    }

    /** Milliseconds as whole ticks, rounded to nearest, at the arena's tick rate. */
    public fun ticksOf(millis: Int): Int {
        val rate = arena.ctx.clock.tickRate
        return (millis.toLong() * rate + MILLIS_PER_SECOND / 2).toInt() / MILLIS_PER_SECOND
    }

    /** The authoritative reading: the server's own live state, which nothing else may write. */
    public fun serverState(client: Int): PeerSnapshot {
        val index = checkedClient(client)
        val avatar = arena.avatar(requireNotNull(avatars[index]) { "client $index has no avatar" })
        return PeerSnapshot(
            peer = PeerId.SERVER.toString(),
            tick = arena.tick,
            x = avatar.x,
            y = avatar.y,
            owner = avatar.owner,
            inputsSent = 0L,
            inputsApplied = held[index].applied,
            stats = harness.transport(PeerId.SERVER).stats(PeerId.client(index)),
        )
    }

    /** What [client] believes, which is what the server told it as of its last applied packet. */
    public fun clientState(client: Int): PeerSnapshot {
        val index = checkedClient(client)
        val end = ends[index - 1]
        val netId = requireNotNull(avatars[index]) { "client $index has no avatar" }
        val row = end.world.rowOf(netId)
        val slot = if (row == ReplicaStore.ABSENT) ReplicaStore.ABSENT else end.world.slotOf(row, avatarComponent)
        val store = end.world.storeAt(avatarComponent)
        return PeerSnapshot(
            peer = end.peer.toString(),
            tick = end.serverTick,
            x = if (slot == ReplicaStore.ABSENT) null else store.getFloat(slot, NetAvatarReplicator.X),
            y = if (slot == ReplicaStore.ABSENT) null else store.getFloat(slot, NetAvatarReplicator.Y),
            owner = if (slot == ReplicaStore.ABSENT) null else store.getInt(slot, NetAvatarReplicator.OWNER),
            inputsSent = held[index].sent,
            inputsApplied = held[index].applied,
            stats = harness.transport(end.peer).stats(PeerId.SERVER),
        )
    }

    /**
     * One client's desync against the server, and the tick it was measured at.
     *
     * ## The tick is the whole subtlety
     *
     * A client under 200ms of latency is a dozen ticks behind by construction, so comparing it
     * against the server's *newest* capture would report a desync on every field on every call
     * and would be asserting that replication is instantaneous rather than that it is correct.
     * The comparison is therefore made against the server snapshot at the tick the client last
     * applied - which the client knows, because it rides in every packet header - read back out
     * of the ring that is also the baseline store.
     */
    public fun desync(client: Int): DesyncMeasurement {
        val index = checkedClient(client)
        val end = ends[index - 1]
        val captured = arena.stateAt(end.serverTick)
            ?: return DesyncMeasurement(end.serverTick, null, emptyList())
        return DesyncMeasurement(
            end.serverTick,
            captured.tick,
            DesyncReport.compare(arena.registry, captured.fields, end.world),
        )
    }

    /** Releases every link and drops undelivered traffic. Idempotent. */
    public fun close() {
        harness.close()
    }

    override fun toString(): String = "NetSession($clients clients, seed=$seed, tick=${tick.value})"

    private fun checkedClient(client: Int): Int {
        require(client in 1..clients) {
            "this session has clients 1..$clients; there is no client $client"
        }
        return client
    }

    /**
     * The server end: consume input, simulate, capture, broadcast.
     *
     * Input is consumed **before** the capture, so a command that arrived this tick is reflected
     * in the snapshot that goes out this tick. Consuming after would delay every input by one
     * tick for no reason and would make the arena's own latency indistinguishable from the
     * link's.
     */
    private inner class ServerEndpoint : NetEndpoint {

        override val peer: PeerId = PeerId.SERVER

        override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
            server.onPacket(from, buffer, offset, length)
        }

        override fun onTick(tick: Tick) {
            for (index in 1..clients) {
                val command = server.jitterOf(PeerId.client(index)).consume(arena.tick) ?: continue
                val netId = avatars[index] ?: continue
                arena.applyInput(netId, command)
                held[index].applied++
            }
            arena.captureTick()
            val captured = arena.newest() ?: error("the arena captured nothing this tick")
            solveFog()
            server.broadcast(captured)
        }
    }

    /** A client end: sample the held input, send it, apply what came back. */
    private inner class ClientEndpoint(private val end: ReplicationClient) : NetEndpoint {

        override val peer: PeerId = end.peer

        override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
            end.onPacket(buffer, offset, length)
        }

        override fun onTick(tick: Tick) {
            val input = held[end.peer.raw]
            end.pushInput(
                MoveInput(
                    seq = (end.input.produced and PacketHeader.SEQ_MASK.toLong()).toInt(),
                    tick = tick,
                    moveX = input.moveX,
                    moveY = input.moveY,
                    aim = input.aim,
                    buttons = input.buttons,
                ),
            )
            input.sent++
            end.sendTick(tick)
        }
    }

    public companion object {

        /** Eight clients. Past that this is a load test, and a load test wants a socket. */
        public const val MAX_CLIENTS: Int = 8

        /**
         * Ticks one `net.step` call will run.
         *
         * Ten seconds of simulation at 60Hz. A tool runs to completion inside one barrier drain,
         * so a step is bounded rather than open-ended: the bound turns "the agent asked for a
         * million ticks" into a refusal naming the limit instead of a game that stopped
         * answering.
         */
        public const val MAX_STEP: Int = 600

        private const val MILLIS_PER_SECOND: Int = 1_000

        /** Two sides, so `clients = 2` already puts one client in the other's fog. */
        public const val FOG_TEAMS: Int = 2

        /** Half the fog grid's width in world units. Far past anything a stepped avatar reaches. */
        private const val FOG_EXTENT: Float = 512f

        /** Three sight radii between spawns: outside sight even with the hysteresis band. */
        private const val SPAWN_SPACING_RADII: Float = 3f

        private const val FOG_CELL: Float = 16f
        private const val FOG_CELLS: Int = 64
    }
}

/** A desync comparison and the ticks it was actually made between. */
public class DesyncMeasurement(
    /** The server tick the client last applied. */
    public val clientTick: Tick,
    /** The tick the ring actually held for it, or null when it had aged out. */
    public val comparedAt: Tick?,
    /** Every replicated field the two disagree on. Empty means converged. */
    public val fields: List<Desync>,
) {
    /** Whether the client has converged at [comparedAt]. */
    public val converged: Boolean get() = comparedAt != null && fields.isEmpty()

    override fun toString(): String = "DesyncMeasurement(at=$comparedAt, ${fields.size} field(s))"
}
