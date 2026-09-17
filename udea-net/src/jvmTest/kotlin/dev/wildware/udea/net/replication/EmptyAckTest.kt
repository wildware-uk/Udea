package dev.wildware.udea.net.replication

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.net.wire.ProtocolDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Sequence zero is a real sequence**, so "I have acknowledged nothing" needs a bit of its own.
 *
 * ## The defect
 *
 * A client must be able to send before it has received: its first input cannot wait on the first
 * snapshot. `ReplicationClient` had nothing to put in the `ack` field at that moment and wrote
 * `0`. The server read that as "your packet 0 arrived", and an ack on this server does one very
 * specific thing - it **promotes the baseline** of every entity that packet carried, to the tick
 * that packet was built at.
 *
 * So if packet 0 was the one the link dropped, the server spent the rest of the session
 * delta-encoding those entities against a tick the client had never held, and every field of
 * theirs that did not happen to change again was wrong for ever. On a perfect link it is
 * invisible, because packet 0 arrived anyway. That is the shape of a bug that ships.
 *
 * ## The fix, and why it is a flag rather than a sentinel
 *
 * `PacketHeader` bit 1 of the flags byte - already in the wire, previously unassigned - says
 * whether `ack`/`ackBits` mean anything. No sentinel value could have done the job: every value
 * of a 16-bit sequence field is reachable.
 */
class EmptyAckTest {

    private val world = NetTestWorld(seed = SEED)
    private val protocol = ProtocolDescriptor.of(world.registry)

    @Test
    fun `a client that has received nothing sends a packet whose ack means nothing`() {
        val network = LoopbackNetwork(ManualClock())
        val client = ReplicationClient(
            PeerId.client(1),
            world.registry,
            protocol,
            network.transportFor(PeerId.client(1)),
        )
        // An input tick, with a command to send and nothing ever received.
        client.pushInput(dev.wildware.udea.net.input.MoveInput(0, Tick.ZERO, 1f, 0f, 0f, 0))
        assertTrue(client.sendTick(Tick.ZERO) > 0, "the client sent nothing, so there is nothing to read")

        val header = headerOf(network, PeerId.SERVER)
        assertEquals(-1, client.remoteSeq, "this client has applied nothing")
        assertFalse(
            header.hasAck,
            "a client that has received nothing claimed an acknowledgement; the server would " +
                "read it as an ack of sequence 0 and promote a baseline the client never held",
        )
    }

    @Test
    fun `the server ignores that ack, so no baseline is promoted by a client that has received nothing`() {
        val network = LoopbackNetwork(ManualClock())
        val server = ReplicationServer(
            registry = world.registry,
            protocol = protocol,
            transport = network.transportFor(PeerId.SERVER),
            ring = world.ring,
        )
        val state = server.addClient(PeerId.client(1))
        world.spawn(x = 1f, y = 2f, teamId = 1)

        // One real outgoing packet, which opens record 0 exactly as a live session does.
        server.send(PeerId.client(1), world.captureTick())
        assertEquals(0L, state.ackedPackets)

        // ...and the packet a fresh client sends before it has received anything.
        server.onPacket(PeerId.client(1), emptyAck(), 0, emptyAckLength)

        assertEquals(
            0L,
            state.ackedPackets,
            "the server acknowledged its own sequence 0 off a client that has received nothing",
        )
        assertEquals(Tick.ZERO, state.lastAckedTick)
    }

    @Test
    fun `a real ack still promotes the baseline, so the flag does not simply disable acking`() {
        // Without this, the two assertions above are equally satisfied by a server that ignores
        // every ack - which converges beautifully and sends full state for ever.
        val network = LoopbackNetwork(ManualClock())
        val server = ReplicationServer(
            registry = world.registry,
            protocol = protocol,
            transport = network.transportFor(PeerId.SERVER),
            ring = world.ring,
        )
        val state = server.addClient(PeerId.client(1))
        world.spawn(x = 1f, y = 2f, teamId = 1)
        server.send(PeerId.client(1), world.captureTick())

        server.onPacket(PeerId.client(1), realAck(seq = 0), 0, realAckLength)

        assertEquals(1L, state.ackedPackets, "a genuine ack was ignored")
    }

    private var emptyAckLength = 0
    private var realAckLength = 0

    /** The datagram a client with `remoteSeq < 0` writes: ack fields present, flag clear. */
    private fun emptyAck(): ByteArray = datagram(hasAck = false, ack = 0).also { emptyAckLength = lastLength }

    /** The same datagram from a client that has genuinely applied [seq]. */
    private fun realAck(seq: Int): ByteArray = datagram(hasAck = true, ack = seq).also { realAckLength = lastLength }

    private var lastLength = 0

    private fun datagram(hasAck: Boolean, ack: Int): ByteArray {
        val buffer = ByteArray(BUFFER_BYTES)
        val writer = BitBufferWriter(buffer)
        PacketHeader(
            protoHash = protocol.protoHash,
            seq = 0,
            ack = ack,
            ackBits = 0,
            serverTick = Tick.ZERO,
            baselineTick = Tick.ZERO,
            hasBaseline = false,
            hasAck = hasAck,
        ).write(writer)
        lastLength = writer.byteLength
        return buffer
    }

    /** Reads back the header of the newest datagram queued for [peer]. */
    private fun headerOf(network: LoopbackNetwork, peer: PeerId): PacketHeader {
        var header: PacketHeader? = null
        network.transportFor(peer).poll { _, buffer: ByteArray, offset: Int, length: Int ->
            header = PacketHeader.read(BitBufferReader(buffer, offset, length))
        }
        return checkNotNull(header) { "nothing was delivered to $peer" }
    }

    private companion object {
        const val SEED: Long = 20_260_823L
        const val BUFFER_BYTES = 256
    }
}
