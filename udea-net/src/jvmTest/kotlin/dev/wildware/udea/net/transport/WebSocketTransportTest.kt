package dev.wildware.udea.net.transport

import dev.wildware.udea.net.replication.ReplicationClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [WebSocketTransport] against [WebSocketServerTransport], both on this JVM, over a real loopback
 * TCP connection (issue #209).
 *
 * The Wasm client test is the headline; this is the same exchange with both ends in reach of a
 * debugger, plus the refusals a browser client meets: a different build, a full server, a server
 * that is not there.
 *
 * Unlike [UdpPair], delivery here is not synchronous inside a step. A WebSocket's bytes are read by
 * a coroutine and handed to [Transport.poll] through a queue (spec section 6), so every wait below
 * is a bounded [pump]: step, yield a millisecond to the I/O, check, up to a deadline that fails the
 * test by name rather than hanging it.
 */
class WebSocketTransportTest {

    @Test
    fun `snapshots stream from the server to a client and its acknowledgements flow back`() = runBlocking<Unit> {
        WebSocketSnapshotServer().use { server ->
            val socket = WebSocketTransport.client(server.url, server.protocol.protoHash)
            socket.use {
                val client = ReplicationClient(PeerId.client(1), server.world.registry, server.protocol, socket)
                var lastMismatch: String? = "never pumped"
                pump({
                    server.step()
                    socket.poll { _, buffer, offset, length -> client.onPacket(buffer, offset, length) }
                    if (socket.isConnected) client.sendTick(client.serverTick)
                }) {
                    lastMismatch = WebSocketSnapshotScenario.mismatch(client)
                    lastMismatch == null
                }

                assertNull(lastMismatch, "the client's replica never matched the server")
                assertEquals(PeerId.client(1), socket.localPeer)
                assertEquals(listOf(PeerId.client(1)), server.events.connected)
                assertTrue(
                    server.transport.stats(PeerId.client(1)).packetsReceived > 0L,
                    "the server received nothing from the client, so no snapshot was acknowledged",
                )
            }
        }
    }

    @Test
    fun `payload bytes arrive whole and in order in both directions`() = runBlocking<Unit> {
        WebSocketServerTransport.start(protoHash = TEST_PROTO_HASH).use { server ->
            WebSocketTransport.client(server.url, TEST_PROTO_HASH).use { client ->
                val serverSink = RecordingSink()
                val clientSink = RecordingSink()
                pump({ server.poll(serverSink); client.poll(clientSink) }) { client.isConnected }

                val up = List(5) { index -> ByteArray(40 + index) { (it * 7 + index).toByte() } }
                val down = List(5) { index -> ByteArray(2000 + index) { (it * 13 + index).toByte() } }
                up.forEach { client.send(PeerId.SERVER, it, 0, it.size) }
                down.forEach { server.send(PeerId.client(1), it, 0, it.size) }
                pump({ server.poll(serverSink); client.poll(clientSink) }) {
                    serverSink.messages.size == up.size && clientSink.messages.size == down.size
                }

                up.forEachIndexed { index, bytes -> assertContentEquals(bytes, serverSink.messages[index]) }
                down.forEachIndexed { index, bytes -> assertContentEquals(bytes, clientSink.messages[index]) }
                assertEquals(List(up.size) { PeerId.client(1) }, serverSink.senders)
                assertEquals(List(down.size) { PeerId.SERVER }, clientSink.senders)
                assertEquals(up.sumOf { it.size }.toLong(), server.stats(PeerId.client(1)).bytesReceived)
            }
        }
    }

    @Test
    fun `a client built against a different protocol is denied by name`() = runBlocking<Unit> {
        val serverEvents = RecordingListener()
        val clientEvents = RecordingListener()
        WebSocketServerTransport.start(protoHash = TEST_PROTO_HASH, listener = serverEvents).use { server ->
            WebSocketTransport.client(server.url, TEST_PROTO_HASH xor 1, listener = clientEvents).use { client ->
                pump({ server.poll(RecordingSink()); client.poll(RecordingSink()) }) { client.failure != null }

                assertEquals(DisconnectReason.ProtocolMismatch, client.failure)
                assertEquals(listOf(PeerId.SERVER to DisconnectReason.ProtocolMismatch), clientEvents.disconnected)
                assertEquals(emptyList(), serverEvents.connected)
                assertEquals(emptyList(), server.connections())
                assertTrue(!client.isConnected)
                assertEquals(1L, server.counters.handshakesDenied)
                assertEquals(0L, server.counters.handshakesCompleted)
            }
        }
    }

    @Test
    fun `a full server denies the next client and keeps the first`() = runBlocking<Unit> {
        WebSocketServerTransport.start(protoHash = TEST_PROTO_HASH, config = WebSocketConfig(maxClients = 1)).use { server ->
            WebSocketTransport.client(server.url, TEST_PROTO_HASH).use { first ->
                pump({ server.poll(RecordingSink()); first.poll(RecordingSink()) }) { first.isConnected }
                WebSocketTransport.client(server.url, TEST_PROTO_HASH).use { second ->
                    pump({ server.poll(RecordingSink()); first.poll(RecordingSink()); second.poll(RecordingSink()) }) {
                        second.failure != null
                    }

                    assertEquals(DisconnectReason.ServerFull, second.failure)
                    assertTrue(first.isConnected)
                    assertEquals(listOf(PeerId.client(1)), server.connections())
                    assertEquals(1L, server.counters.handshakesCompleted)
                    assertEquals(1L, server.counters.handshakesDenied)
                }
            }
        }
    }

    @Test
    fun `a client that leaves frees its slot for the next one`() = runBlocking<Unit> {
        val serverEvents = RecordingListener()
        WebSocketServerTransport.start(protoHash = TEST_PROTO_HASH, listener = serverEvents).use { server ->
            val first = WebSocketTransport.client(server.url, TEST_PROTO_HASH)
            pump({ server.poll(RecordingSink()); first.poll(RecordingSink()) }) { first.isConnected }
            first.close()
            pump({ server.poll(RecordingSink()) }) { serverEvents.disconnected.isNotEmpty() }

            assertEquals(listOf(PeerId.client(1) to DisconnectReason.RemoteClosed), serverEvents.disconnected)
            assertEquals(emptyList(), server.connections())

            WebSocketTransport.client(server.url, TEST_PROTO_HASH).use { second ->
                pump({ server.poll(RecordingSink()); second.poll(RecordingSink()) }) { second.isConnected }
                assertEquals(PeerId.client(1), second.localPeer, "the freed slot was not reused")
            }
        }
    }

    @Test
    fun `a server that goes away disconnects its client`() = runBlocking<Unit> {
        val clientEvents = RecordingListener()
        val server = WebSocketServerTransport.start(protoHash = TEST_PROTO_HASH)
        WebSocketTransport.client(server.url, TEST_PROTO_HASH, listener = clientEvents).use { client ->
            pump({ server.poll(RecordingSink()); client.poll(RecordingSink()) }) { client.isConnected }
            server.close()
            pump({ client.poll(RecordingSink()) }) { !client.isConnected }

            assertEquals(listOf(PeerId.SERVER to DisconnectReason.RemoteClosed), clientEvents.disconnected)
        }
    }

    @Test
    fun `a client with no server to reach fails as unreachable`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }
        WebSocketTransport.client("ws://${WebSocketServerTransport.LOOPBACK}:$closedPort/udea", TEST_PROTO_HASH).use { client ->
            pump({ client.poll(RecordingSink()) }) { client.failure != null }
            assertEquals(DisconnectReason.Unreachable, client.failure)
            assertNotNull(client.failureCause, "an unreachable server keeps the refused connect as its cause")
        }
    }

    @Test
    fun `a message over the configured limit is refused at the sender`() = runBlocking<Unit> {
        val config = WebSocketConfig(maxMessageBytes = 256)
        WebSocketServerTransport.start(protoHash = TEST_PROTO_HASH, config = config).use { server ->
            WebSocketTransport.client(server.url, TEST_PROTO_HASH, config).use { client ->
                pump({ server.poll(RecordingSink()); client.poll(RecordingSink()) }) { client.isConnected }
                val tooBig = ByteArray(257)

                val refused = assertFailsWith<DatagramTooLargeException> { client.send(PeerId.SERVER, tooBig, 0, tooBig.size) }
                assertEquals(257, refused.length)
                assertFailsWith<DatagramTooLargeException> { server.send(PeerId.client(1), tooBig, 0, tooBig.size) }
            }
        }
    }

    @Test
    fun `a send to a peer with no connection is counted rather than thrown`() = runBlocking<Unit> {
        WebSocketServerTransport.start(protoHash = TEST_PROTO_HASH).use { server ->
            val bytes = byteArrayOf(1, 2, 3)
            server.send(PeerId.client(7), bytes, 0, bytes.size)
            assertEquals(1L, server.counters.sendsToUnknownPeer)
        }
    }

    @Test
    fun `a connection that opens with anything but a hello is counted and dropped`() = runBlocking<Unit> {
        val serverEvents = RecordingListener()
        WebSocketServerTransport.start(protoHash = TEST_PROTO_HASH, listener = serverEvents).use { server ->
            HttpClient(CIO) { install(WebSockets) }.use { http ->
                http.webSocket(server.url) {
                    send(Frame.Binary(true, WebSocketLayout.payload(byteArrayOf(1, 2, 3), 0, 3)))
                    pump({ server.poll(RecordingSink()) }) { server.counters.malformed == 1L }
                    // The server closes a connection it cannot make sense of rather than holding it.
                    val ending = withTimeoutOrNull(PUMP_DEADLINE) { incoming.receiveCatching() }
                    assertTrue(ending?.isClosed == true, "the server left the connection open: $ending")
                }
            }
            assertEquals(emptyList(), serverEvents.connected)
            assertEquals(emptyList(), server.connections())
            assertEquals(0L, server.counters.handshakesCompleted)
        }
    }

    @Test
    fun `the server runs without kotlin-reflect on the classpath`() {
        // `ktor-server-core` asks for kotlin-reflect, and UDEA-MG-005 bans it from the shipped game,
        // which depends on this module. `build.gradle.kts` excludes it; every server test in this
        // class ran on this classpath, so this is what says they ran without it.
        assertFailsWith<ClassNotFoundException> { Class.forName("kotlin.reflect.full.KClasses") }
    }

    private suspend fun pump(step: () -> Unit, condition: () -> Boolean) {
        val held = withTimeoutOrNull(PUMP_DEADLINE) {
            while (true) {
                step()
                if (condition()) return@withTimeoutOrNull true
                delay(1)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        assertTrue(held == true, "the condition did not hold within $PUMP_DEADLINE")
    }

    private companion object {

        /** A deadline, not a budget: loopback converges in milliseconds, and this only bounds a hang. */
        val PUMP_DEADLINE = 20.seconds
    }
}
