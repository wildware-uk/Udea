package dev.wildware.udea.net.replication

import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.ProtocolDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * **A peer id is a slot, and slots are reused.** Whoever gets one next must inherit nothing.
 *
 * `UdpTransport` assigns a client the lowest free slot, so the peer id of a player who has just
 * left is very often the peer id of the next player to arrive - the two-process proof kills one
 * client and watches the next take the same `client1`. Before [ReplicationServer.removeClient]
 * existed, [ReplicationServer.addClient] was a `getOrPut`: the arriving connection was handed the
 * departed one's [ClientReplicationState], including its acked baseline ticks. The server would
 * then delta-encode the new client's entities against ticks a *different person's* machine had
 * acknowledged. Nothing errors. The deltas decode perfectly. Every field is wrong.
 *
 * There is a second, quieter cost: a registered peer is packed and sent a datagram every tick for
 * the life of the process, so a server that never forgets a client leaks a full snapshot's work
 * per departed player per tick, for ever.
 */
class ClientSlotReuseTest {

    private val world = NetTestWorld(seed = SEED)
    private val protocol = ProtocolDescriptor.of(world.registry)
    private val network = LoopbackNetwork(ManualClock())
    private val server = ReplicationServer(
        registry = world.registry,
        protocol = protocol,
        transport = network.transportFor(PeerId.SERVER),
        ring = world.ring,
    )

    @Test
    fun `a slot reused by a new connection carries none of the departed peer's baselines`() {
        val slot = PeerId.client(1)
        val first = server.addClient(slot)
        val netId = world.spawn(x = 1f, y = 2f, teamId = 1)

        // Play a little, and let the first client acknowledge what it was sent.
        repeat(ACKED_PACKETS) {
            server.send(slot, world.captureTick())
            first.applyAck(it, 0)
        }
        assertTrue(first.ackedPackets > 0, "the first client acknowledged nothing, so it has no baseline to leak")
        assertTrue(
            first.baselineTickOf(netId) != ClientReplicationState.NO_BASELINE,
            "the first client holds no baseline for $netId, so this test proves nothing",
        )

        assertTrue(server.removeClient(slot), "the departed peer was not registered")
        assertEquals(emptyList(), server.clients(), "a removed peer is still broadcast to")

        // ...and somebody else arrives into the same slot.
        val second = server.addClient(slot)

        assertNotSame(first, second, "the new connection was handed the departed peer's state")
        assertEquals(0L, second.ackedPackets, "the new connection inherited an acknowledgement count")
        assertEquals(
            ClientReplicationState.NO_BASELINE,
            second.baselineTickOf(netId),
            "the new connection inherited a baseline acknowledged by somebody who has left; the " +
                "next delta would be encoded against a state this machine has never held",
        )
    }

    @Test
    fun `removing a peer that was never registered is a no-op rather than an error`() {
        assertEquals(false, server.removeClient(PeerId.client(7)))
    }

    private companion object {
        const val SEED: Long = 20_260_823L

        /** Enough packets that a baseline is genuinely established, and few enough to stay quick. */
        const val ACKED_PACKETS = 4
    }
}
