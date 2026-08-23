package dev.wildware.udea.net.transport

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [UdpTransport] over real loopback sockets: the handshake, the payload path, fragments, and
 * sequence wraparound.
 *
 * Real sockets rather than a double, because the whole point of this class is the socket. What
 * is *not* real is the clock — every timeout here is a tick the test advances — so none of these
 * tests can be made flaky by a slow machine.
 */
class UdpTransportTest {

    @Test
    fun `the three-way handshake assigns a peer id and opens both ends`() {
        UdpPair().use { pair ->
            val peer = pair.connect()

            assertEquals(PeerId.client(1), peer, "the first client should be client1")
            assertEquals(listOf(PeerId.client(1)), pair.serverEvents.connected)
            assertEquals(listOf(PeerId.SERVER), pair.clientEvents.connected)
            assertEquals(listOf(PeerId.client(1)), pair.server.connections())
            assertEquals(1L, pair.server.counters.handshakesCompleted)
            assertEquals(0L, pair.server.counters.tokenRejected)
            assertEquals(0L, pair.server.counters.malformed)
        }
    }

    @Test
    fun `payload bytes survive a round trip in both directions`() {
        UdpPair().use { pair ->
            pair.connect()
            val up = "input from the client".toByteArray()
            val down = "a snapshot from the server".toByteArray()

            pair.client.send(PeerId.SERVER, up, 0, up.size)
            assertTrue(pair.pumpUntil { pair.serverSink.messages.isNotEmpty() }, "nothing reached the server")
            pair.server.send(PeerId.client(1), down, 0, down.size)
            assertTrue(pair.pumpUntil { pair.clientSink.messages.isNotEmpty() }, "nothing reached the client")

            assertContentEquals(up, pair.serverSink.messages.first())
            assertEquals(PeerId.client(1), pair.serverSink.senders.first())
            assertContentEquals(down, pair.clientSink.messages.first())
            assertEquals(PeerId.SERVER, pair.clientSink.senders.first())
        }
    }

    @Test
    fun `a message larger than the MTU is fragmented and rebuilt byte for byte`() {
        UdpPair().use { pair ->
            pair.connect()
            // Three datagrams' worth, with a partial last fragment, so the test covers the
            // "every fragment but the last is full" rule from both sides.
            val message = ByteArray(3 * UdpConfig().mtu + 137) { (it * 31 + 7).toByte() }

            pair.server.send(PeerId.client(1), message, 0, message.size)

            assertTrue(pair.pumpUntil { pair.clientSink.messages.isNotEmpty() }, "the message never rebuilt")
            assertContentEquals(message, pair.clientSink.messages.single())
        }
    }

    @Test
    fun `a message beyond what reassembly will hold is refused at the sender`() {
        UdpPair().use { pair ->
            pair.connect()
            val tooBig = ByteArray(pair.server.maxMessageBytes + 1)

            val failure = assertFailsWith<DatagramTooLargeException> {
                pair.server.send(PeerId.client(1), tooBig, 0, tooBig.size)
            }

            assertEquals(tooBig.size, failure.length)
            assertEquals(pair.server.maxMessageBytes, failure.mtu)
        }
    }

    @Test
    fun `an idle link is held open by keep-alives and does not time out`() {
        val config = UdpConfig(timeoutTicks = 40L, keepAliveIntervalTicks = 4L)
        UdpPair(serverConfig = config).use { pair ->
            pair.connect()

            // Four whole timeout windows with no application traffic whatsoever.
            pair.step(4 * config.timeoutTicks.toInt())

            assertTrue(pair.server.isConnected, "the server dropped an idle client: ${pair.serverEvents.disconnected}")
            assertTrue(pair.client.isConnected, "the client dropped an idle server")
            assertEquals(emptyList(), pair.serverEvents.disconnected)
        }
    }

    @Test
    fun `a peer that stops answering is timed out and reported`() {
        val config = UdpConfig(timeoutTicks = 30L, keepAliveIntervalTicks = 4L)
        UdpPair(serverConfig = config).use { pair ->
            pair.connect()

            // The client process "dies": it stops being polled and stops sending anything.
            pair.clock.advance()
            repeat(config.timeoutTicks.toInt() * 2) {
                pair.clock.advance()
                pair.server.flush()
                pair.server.poll(pair.serverSink)
            }

            assertEquals(
                listOf(PeerId.client(1) to DisconnectReason.Timeout),
                pair.serverEvents.disconnected,
            )
            assertTrue(pair.server.connections().isEmpty(), "the connection slot was not released")
        }
    }

    @Test
    fun `a released slot is handed to the next client`() {
        val config = UdpConfig(maxClients = 1, timeoutTicks = 20L, keepAliveIntervalTicks = 4L)
        UdpServerOnly(config).use { fixture ->
            RawPeer(fixture.address).use { first ->
                assertTrue(first.handshake(clientSalt = 1L, pump = { fixture.step() }), "the first peer never connected")
                assertEquals(1, first.assignedPeer)
            }
            fixture.step(config.timeoutTicks.toInt() * 2)
            assertTrue(fixture.server.connections().isEmpty(), "slot 1 was never released")

            RawPeer(fixture.address).use { second ->
                assertTrue(second.handshake(clientSalt = 2L, pump = { fixture.step() }), "the slot was not reusable")
                assertEquals(1, second.assignedPeer, "the released slot should be reused")
            }
        }
    }

    @Test
    fun `the server refuses a client whose protocol hash differs, and says why`() {
        UdpPair(protoHash = TEST_PROTO_HASH, clientProtoHash = TEST_PROTO_HASH xor 1).use { pair ->
            assertTrue(
                pair.pumpUntil { pair.client.failure != null },
                "the client was never told: ${pair.server.counters}",
            )

            assertEquals(DisconnectReason.ProtocolMismatch, pair.client.failure)
            assertTrue(pair.server.connections().isEmpty(), "a mismatched client took a slot")
            assertEquals(1L, pair.server.counters.handshakesDenied)
        }
    }

    @Test
    fun `a full server denies rather than dropping the request on the floor`() {
        UdpPair(serverConfig = UdpConfig(maxClients = 1)).use { pair ->
            pair.connect()

            val second = UdpTransport.client(
                serverAddress = loopback(pair.server.localAddress.port),
                clientSalt = 0xBEEFL,
                clock = pair.clock,
                protoHash = TEST_PROTO_HASH,
                config = UdpConfig(maxClients = 1),
            )
            second.use {
                repeat(UdpPair.DEFAULT_LIMIT) {
                    pair.clock.advance()
                    pair.server.flush()
                    second.flush()
                    pair.server.poll(pair.serverSink)
                    second.poll(pair.clientSink)
                    if (second.failure != null) return@repeat
                }
                assertEquals(DisconnectReason.ServerFull, second.failure)
                assertEquals(listOf(PeerId.client(1)), pair.server.connections())
            }
        }
    }

    @Test
    fun `a client whose server never answers gives up with a handshake timeout`() {
        val clock = ManualClock()
        val events = RecordingListener()
        val sink = RecordingSink()
        // Port 1 on loopback: nothing is listening, and nothing in this test binds it.
        val client = UdpTransport.client(
            serverAddress = loopback(1),
            clientSalt = 7L,
            clock = clock,
            protoHash = TEST_PROTO_HASH,
            config = UdpConfig(connectTimeoutTicks = 25L),
            listener = events,
        )

        client.use {
            repeat(60) {
                clock.advance()
                client.flush()
                client.poll(sink)
            }
        }

        assertEquals(DisconnectReason.HandshakeTimeout, client.failure)
        assertEquals(listOf(PeerId.SERVER to DisconnectReason.HandshakeTimeout), events.disconnected)
    }

    @Test
    fun `sequence numbers wrap and delivery continues across 200000 real datagrams`() {
        UdpPair().use { pair ->
            pair.connect()
            val received = HashSet<Int>()
            val recorder = DatagramSink { _, buffer, offset, _ ->
                received += ByteBuffer.wrap(buffer, offset, Int.SIZE_BYTES).int
            }
            val body = ByteArray(Int.SIZE_BYTES)
            val view = ByteBuffer.wrap(body)

            var sent = 0
            while (sent < TOTAL_PACKETS) {
                repeat(PACKETS_PER_STEP) {
                    if (sent >= TOTAL_PACKETS) return@repeat
                    view.putInt(0, sent)
                    pair.client.send(PeerId.SERVER, body, 0, body.size)
                    sent++
                }
                pair.clock.advance()
                pair.server.flush()
                pair.client.flush()
                pair.server.poll(recorder)
                pair.client.poll(pair.clientSink)
            }
            repeat(DRAIN_STEPS) {
                pair.clock.advance()
                pair.server.poll(recorder)
            }

            // 200,000 datagrams is three and a bit full trips round a 16-bit sequence space.
            assertEquals(TOTAL_PACKETS, sent)
            assertEquals(0L, pair.server.counters.replayed, "a wrapped sequence was mistaken for a replay")
            assertEquals(0L, pair.server.counters.malformed)
            assertEquals(0L, pair.server.counters.unknownConnection)
            assertTrue(pair.server.isConnected, "the connection did not survive the wraps")
            // Loopback UDP may still drop under burst, so the assertion is about acceptance
            // continuing, not about zero loss: the highest index delivered has to be past the
            // third wrap, and the overwhelming majority has to have arrived.
            assertTrue(
                received.max() >= TOTAL_PACKETS - PACKETS_PER_STEP * 2,
                "delivery stopped at ${received.max()} of $TOTAL_PACKETS",
            )
            assertTrue(
                received.size >= TOTAL_PACKETS * 95 / 100,
                "only ${received.size} of $TOTAL_PACKETS arrived, which is loss, not wraparound",
            )
        }
    }

    private companion object {

        /** Just over three wraps of the 16-bit sequence space, as the Phase 4 exit requires. */
        const val TOTAL_PACKETS: Int = 200_000

        /** Sends per tick. Small enough that the receive socket buffer is never the bottleneck. */
        const val PACKETS_PER_STEP: Int = 200

        /** Ticks spent draining after the last send. */
        const val DRAIN_STEPS: Int = 64
    }
}
