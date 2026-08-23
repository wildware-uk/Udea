package dev.wildware.udea.net.harness

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.replication.ReplicationServer
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.NetEndpoint
import dev.wildware.udea.net.transport.NetHarness
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.ProtocolDescriptor

/**
 * A whole replicated session: one server world, n clients, one in-memory network, one thread.
 *
 * The server captures through the real `SnapshotService` into the real `SnapshotRing` and reads
 * its baselines back out of that same ring, which is the point: spec 3.1's claim that the ring
 * *is* the baseline store is only tested if the baselines actually come from it.
 */
internal class ReplicationSession(
    clientCount: Int = 1,
    seed: Long = 20_260_823L,
    conditions: NetConditions = NetConditions.PERFECT,
    budgetBytes: Int = BandwidthBudget.DEFAULT_BYTES_PER_PACKET,
    jitterCapacity: Int = dev.wildware.udea.net.input.JitterBuffer.DEFAULT_CAPACITY,
    /** Runs on the server immediately before each tick's capture, to move the world. */
    private val mutate: (Tick) -> Unit = {},
    /** Runs on each client immediately before its send, to produce input. */
    private val clientTick: (ReplicationClient, Tick) -> Unit = { _, _ -> },
) {

    val harness: NetHarness = NetHarness(clientCount, seed, initialConditions = conditions)
    val world: NetTestWorld = NetTestWorld(seed)
    val protocol: ProtocolDescriptor = ProtocolDescriptor.of(world.registry)

    val server: ReplicationServer = ReplicationServer(
        registry = world.registry,
        protocol = protocol,
        transport = harness.transport(PeerId.SERVER),
        ring = world.ring,
        budget = BandwidthBudget(budgetBytes),
        jitterCapacity = jitterCapacity,
    )

    /** Every datagram a client sent to the server, copied at the moment it arrived. */
    val clientToServer: MutableList<ByteArray> = mutableListOf()

    val clients: List<ReplicationClient> = (1..clientCount).map { index ->
        val peer = PeerId.client(index)
        server.addClient(peer)
        ReplicationClient(peer, world.registry, protocol, harness.transport(peer))
    }

    init {
        harness.register(
            object : NetEndpoint {
                override val peer: PeerId = PeerId.SERVER

                override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                    clientToServer += buffer.copyOfRange(offset, offset + length)
                    server.onPacket(from, buffer, offset, length)
                }

                override fun onTick(tick: Tick) {
                    mutate(tick)
                    world.captureTick()
                    server.broadcast(serverState())
                }
            },
        )
        for (client in clients) {
            harness.register(
                object : NetEndpoint {
                    override val peer: PeerId = client.peer

                    override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                        client.onPacket(buffer, offset, length)
                    }

                    override fun onTick(tick: Tick) {
                        clientTick(client, tick)
                        client.sendTick(tick)
                    }
                },
            )
        }
    }

    fun step(ticks: Int): Tick = harness.step(ticks)

    /** The server's captured state at its current tick. */
    fun serverState(): WorldSnapshot =
        world.ring.nearestAtOrBefore(world.ctx.clock.tick) ?: error("the ring holds no snapshot yet")

    /**
     * The server's captured state at [tick] exactly.
     *
     * Convergence is asserted against the tick the client actually holds, not the server's
     * newest: the server captures and sends at T, and the earliest the client can have applied
     * that is T + 1, so comparing against the newest capture would assert that replication is
     * instantaneous rather than that it is correct.
     */
    fun serverStateAt(tick: Tick): WorldSnapshot {
        val snapshot = world.ring.nearestAtOrBefore(tick) ?: error("the ring holds nothing at or before $tick")
        check(snapshot.tick == tick) { "the ring no longer holds $tick; nearest is ${snapshot.tick}" }
        return snapshot
    }
}
