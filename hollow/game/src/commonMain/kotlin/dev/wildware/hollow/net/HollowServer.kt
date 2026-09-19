package dev.wildware.hollow.net

import dev.wildware.hollow.HollowGame
import dev.wildware.hollow.HollowLevel
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotRing
import dev.wildware.udea.core.snapshot.SnapshotTimeTravel
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.replication.ReplicationServer
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.wire.ProtocolDescriptor

/**
 * The authoritative Hollow server: a headless [HollowGame] playing [level], replicating its world to
 * every client over [transport] (issue #249). `MobaHostSession` is moba's, with champions, input
 * and RPCs that Hollow does not have yet.
 *
 * Written against [Transport] alone, so the in-process harness a test drives and a UDP socket are
 * the same thing to it.
 *
 * One [tick] is one simulation step: the world advances, the snapshot ring captures it, and every
 * client is sent what it has not acknowledged against its own baseline.
 */
public class HollowServer(
    private val transport: Transport,
    budget: BandwidthBudget = BandwidthBudget(),
    mtu: Int = LoopbackNetwork.DEFAULT_MTU,
    level: ByteArray = HollowLevel.bundledBytes(),
) : AutoCloseable {

    /** The server's own game, headless, with the level loaded. */
    public val host: GameHost = HollowGame.host(RenderMode.Headless, level = level).also(HollowGame::seed)

    private val travel: SnapshotTimeTravel = checkNotNull(host.game.simulation.travel as? SnapshotTimeTravel) {
        "a replicating server needs a snapshot ring: it is the baseline store (spec 3.1), and " +
            "HollowGame.definition supplies one"
    }

    /** The baselines every client is sent deltas against. */
    public val ring: SnapshotRing get() = travel.ring

    /** What replicates. */
    public val registry: ComponentRegistry get() = ring.registry

    /** This build's protocol. */
    public val protocol: ProtocolDescriptor = HollowNet.protocol(registry)

    /** The replication half: baselines, priority and the wire. */
    public val replication: ReplicationServer = ReplicationServer(
        registry = registry,
        protocol = protocol,
        transport = transport,
        ring = ring,
        budget = budget,
        mtu = mtu,
    )

    private val peers = LinkedHashSet<PeerId>()

    /**
     * Datagrams [onPacket] could not decode. Counted and dropped rather than thrown: a malformed or
     * hostile datagram must not stop the server for everybody else.
     */
    public var malformedPackets: Long = 0L
        private set

    /** The server's simulation tick. */
    public val tick: Tick get() = host.tick

    /** Starts replicating to [peer]. A second call for the same peer does nothing. */
    public fun addClient(peer: PeerId) {
        if (peers.add(peer)) replication.addClient(peer)
    }

    /** Stops replicating to [peer]. False when it was not a client. */
    public fun removeClient(peer: PeerId): Boolean {
        if (!peers.remove(peer)) return false
        replication.removeClient(peer)
        return true
    }

    /** The connected clients, in the order they joined. */
    public fun clients(): List<PeerId> = peers.toList()

    /** A datagram from [from]: acknowledgements, which move that client's baseline on. */
    public fun onPacket(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
        try {
            replication.onPacket(from, buffer, offset, length)
        } catch (malformed: RuntimeException) {
            malformedPackets++
        }
    }

    /** One step: simulate, capture, and send every client its delta. */
    public fun tick() {
        host.run(1)
        travel.captureNow()
        replication.broadcast(state())
    }

    /** The world as the ring captured it at the current tick: what was just sent. */
    public fun state(): WorldSnapshot {
        val slot = checkNotNull(ring.nearestAtOrBefore(host.tick)) { "the server's ring holds nothing at ${host.tick}" }
        check(slot.tick == host.tick) { "the ring's newest slot is ${slot.tick}, not ${host.tick}" }
        return slot
    }

    override fun close() {
        host.stop()
        transport.close()
    }

    override fun toString(): String = "HollowServer(tick=${host.tick}, clients=${peers.size})"
}
