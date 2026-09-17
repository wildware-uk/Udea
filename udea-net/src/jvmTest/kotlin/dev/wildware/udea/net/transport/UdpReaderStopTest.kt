package dev.wildware.udea.net.transport

import io.ktor.network.selector.Selectable
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A socket reader that stops while its [UdpTransport] is still open is reported, not silent
 * (issue #220).
 *
 * Ktor 3.6.0 reads a datagram socket on a coroutine of its own and, when a read throws an
 * `IOException`, completes `incoming` normally: no exception reaches whoever iterates it. Before
 * this issue the transport's reader then simply returned, and the transport stayed "open" and
 * never heard its peer again.
 *
 * These tests reach that exact path rather than a lookalike. [breakRead] makes Ktor's own read
 * fail with a real `IOException`, and Ktor ends `incoming` itself; the transport is not closed
 * and never told. [breakRead] also waits for `incoming` to have really ended, and fails if it
 * does not, so a test cannot pass without having reached the defect.
 */
class UdpReaderStopTest {

    @Test
    fun `a client whose socket reader stops reports the failure and drops its connection`() {
        UdpPair().use { pair ->
            pair.connect()

            breakRead(pair.client)
            assertTrue(pollUntil(pair.client, pair.clientSink) { pair.client.failure != null }, "the client never noticed")

            assertEquals(DisconnectReason.ReceiveFailed, pair.client.failure)
            assertEquals(listOf(PeerId.SERVER to DisconnectReason.ReceiveFailed), pair.clientEvents.disconnected)
            assertEquals(false, pair.client.isConnected)
            assertEquals(1L, pair.client.counters.receiveErrors)
        }
    }

    @Test
    fun `a server whose socket reader stops reports every connection it can no longer hear`() {
        UdpPair().use { pair ->
            pair.connect()

            breakRead(pair.server)
            assertTrue(pollUntil(pair.server, pair.serverSink) { pair.server.failure != null }, "the server never noticed")

            assertEquals(DisconnectReason.ReceiveFailed, pair.server.failure)
            assertEquals(listOf(PeerId.client(1) to DisconnectReason.ReceiveFailed), pair.serverEvents.disconnected)
            assertTrue(pair.server.connections().isEmpty(), "a connection the server cannot hear is still listed")
            assertEquals(1L, pair.server.counters.receiveErrors)
        }
    }

    @Test
    fun `a client still handshaking when its reader stops fails the handshake with the reason`() {
        // The client is bound but never stepped, so no handshake datagram has gone anywhere: it is
        // in `Requesting` with no connection to retire, which is the case `retire` cannot cover.
        UdpPair().use { pair ->
            breakRead(pair.client)
            assertTrue(pollUntil(pair.client, pair.clientSink) { pair.client.failure != null }, "the client never noticed")

            assertEquals(DisconnectReason.ReceiveFailed, pair.client.failure)
            assertEquals(listOf(PeerId.SERVER to DisconnectReason.ReceiveFailed), pair.clientEvents.disconnected)
            assertEquals(false, pair.client.isConnected)
        }
    }

    @Test
    fun `what arrived before the reader stopped is still delivered, and the stop is reported once`() {
        UdpPair().use { pair ->
            pair.connect()
            val last = "sent before the reader stopped".toByteArray()
            pair.client.send(PeerId.SERVER, last, 0, last.size)
            settle(pair.server, pair.client.datagramsTransmitted)

            breakRead(pair.server)
            assertTrue(pollUntil(pair.server, pair.serverSink) { pair.server.failure != null }, "the server never noticed")
            repeat(REPOLLS) { pair.server.poll(pair.serverSink) }

            assertContentEquals(last, pair.serverSink.messages.single())
            assertEquals(listOf(PeerId.client(1) to DisconnectReason.ReceiveFailed), pair.serverEvents.disconnected)
            assertEquals(1L, pair.server.counters.receiveErrors)
        }
    }

    @Test
    fun `a receive channel cancelled underneath an open transport is reported too`() {
        // The other way `incoming` can end without this transport closing it: cancelled rather
        // than completed. The reader sees a cancellation that is not its own.
        UdpPair().use { pair ->
            pair.connect()

            socketOf(pair.client).incoming.cancel()
            assertTrue(pollUntil(pair.client, pair.clientSink) { pair.client.failure != null }, "the client never noticed")

            assertEquals(DisconnectReason.ReceiveFailed, pair.client.failure)
            assertEquals(listOf(PeerId.SERVER to DisconnectReason.ReceiveFailed), pair.clientEvents.disconnected)
        }
    }

    @Test
    fun `closing the transport is not reported as a reader failure`() {
        val pair = UdpPair()
        pair.connect()

        pair.client.close()
        pair.server.close()

        assertNull(pair.client.failure)
        assertNull(pair.server.failure)
        assertEquals(listOf(PeerId.SERVER to DisconnectReason.LocalClosed), pair.clientEvents.disconnected)
        assertEquals(listOf(PeerId.client(1) to DisconnectReason.LocalClosed), pair.serverEvents.disconnected)
        assertEquals(0L, pair.client.counters.receiveErrors)
    }

    private companion object {

        /** Polls after the report, to show a second poll does not report the stop again. */
        const val REPOLLS: Int = 5

        /** A deadline on a loopback socket's reader noticing, not a latency budget. */
        val DEADLINE = 5.seconds

        /**
         * Makes Ktor's own read of [transport]'s socket fail with an `IOException`, leaving the
         * transport and the socket open, and waits for Ktor to end `incoming` because of it.
         *
         * `notifyClosed` is how Ktor's selector wakes a read suspended on a socket: it resumes it
         * with a `ClosedChannelException`, which is an `IOException`, and Ktor's receive loop
         * catches that and completes `incoming` normally. Nothing is closed - the socket still
         * sends - so what the transport sees is exactly a read failing underneath it. Closing the
         * operating-system channel instead does not work: it wakes nothing, and the read stays
         * suspended. Repeated, because a call that lands while the loop is between reads rather
         * than suspended in one has nothing to wake.
         *
         * Reflection, and only here: the socket and selector are private to the transport, and a
         * production seam that exists only so a test can break the socket would be API nobody else
         * calls. A rename of either field fails this loudly rather than letting a test pass.
         */
        @OptIn(DelicateCoroutinesApi::class) // `isClosedForReceive`: polled, so a stale answer only costs a turn
        fun breakRead(transport: UdpTransport) {
            val socket = socketOf(transport)
            val selector = privateField(transport, "selector") as SelectorManager
            val ended = runBlocking {
                withTimeoutOrNull(DEADLINE) {
                    while (!socket.incoming.isClosedForReceive) {
                        selector.notifyClosed(socket as Selectable)
                        yield()
                    }
                    true
                } ?: false
            }
            check(ended) { "Ktor did not end incoming, so this test reaches nothing" }
        }

        fun socketOf(transport: UdpTransport): BoundDatagramSocket = privateField(transport, "socket") as BoundDatagramSocket

        private fun privateField(transport: UdpTransport, name: String): Any =
            UdpTransport::class.java.getDeclaredField(name).apply { isAccessible = true }.get(transport)

        /** Polls [transport] until [condition] holds or [DEADLINE] passes. */
        fun pollUntil(transport: UdpTransport, sink: DatagramSink, condition: () -> Boolean): Boolean = runBlocking {
            withTimeoutOrNull(DEADLINE) {
                while (!condition()) {
                    transport.poll(sink)
                    yield()
                }
                true
            } ?: false
        }
    }
}
