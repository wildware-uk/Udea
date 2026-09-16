package dev.wildware.udea.net.transport

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The desktop server's end of [WebSocketTransport]: every browser, phone and desktop client that
 * connects over a WebSocket, behind the same [Transport] SPI as [UdpTransport.server] (issue #209,
 * spec section 6).
 *
 * JVM only, because the server is the desktop JVM (spec D3) and Ktor's server engine is where it
 * runs. A game that serves both UDP and WebSocket clients runs one of each transport side by side.
 *
 * ## Threads
 *
 * Ktor runs each connection on its own coroutine. Those coroutines touch nothing of this class but
 * two queues: the shared inbox, which they fill with what arrived, and their own connection's
 * outbox, which they drain onto the socket. Every decision - who is admitted, which slot they get,
 * who has gone - is taken inside [poll] on the caller's thread, which is the only thread that ever
 * reads or writes the slot table. So a client appears, and disappears, between two game-loop steps
 * and never during one.
 */
public class WebSocketServerTransport private constructor(
    private val protoHash: Int,
    private val config: WebSocketConfig,
    private val listener: UdpConnectionListener,
    private val host: String,
    requestedPort: Int,
    private val path: String,
) : Transport, AutoCloseable {

    /** One WebSocket connection, admitted or not. */
    private class Link(capacity: Int) {

        /** Messages for this connection's socket. Closed to close the connection. */
        val outbox = Channel<ByteArray>(capacity)

        /** The slot this connection holds, once [poll] has admitted it. Game-loop thread only. */
        var peer: PeerId? = null
    }

    /** What a connection's coroutine hands to [poll]. */
    private sealed interface Inbound {
        val link: Link

        class Joined(override val link: Link, val hello: ByteArray) : Inbound
        class Message(override val link: Link, val bytes: ByteArray) : Inbound
        class Left(override val link: Link) : Inbound
    }

    private val inbox = Channel<Inbound>(config.queueCapacity)
    private val byPeer = arrayOfNulls<Link>(config.maxClients + 1)
    private val statsByPeer = HashMap<Int, TransportStats>()

    /** Written by [close] on the game loop's thread, read by connection coroutines as they end. */
    @Volatile
    private var closed = false

    /** Every refusal, by why. Live, like [TransportStats]. */
    public val counters: WebSocketCounters = WebSocketCounters()

    override val localPeer: PeerId get() = PeerId.SERVER

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = requestedPort, host = host) {
        install(WebSockets)
        routing { webSocket(path) { serve() } }
    }

    /** The TCP port the server is listening on, including an ephemeral one. */
    public val port: Int

    /** The URL a client on this machine connects to. */
    public val url: String get() = "ws://$host:$port$path"

    init {
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    /** Every admitted connection, ascending by peer id. */
    public fun connections(): List<PeerId> = byPeer.mapNotNull { it?.peer }

    override fun send(peer: PeerId, bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "server transport is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "slice [$offset, ${offset + length}) does not fit a ${bytes.size} byte buffer"
        }
        if (length > config.maxMessageBytes) throw DatagramTooLargeException(length, config.maxMessageBytes)
        val link = if (peer.raw in 1..config.maxClients) byPeer[peer.raw] else null
        if (link == null) {
            counters.sendsToUnknownPeer++
            return
        }
        if (link.outbox.trySend(WebSocketLayout.payload(bytes, offset, length)).isSuccess) {
            stats(peer).recordSent(length)
        } else {
            stats(peer).packetsDropped++
        }
    }

    override fun poll(sink: DatagramSink): Int {
        if (closed) return 0
        var delivered = 0
        var taken = 0
        while (taken < config.maxReceivesPerPoll) {
            val inbound = inbox.tryReceive().getOrNull() ?: break
            taken++
            when (inbound) {
                is Inbound.Joined -> admit(inbound.link, inbound.hello)
                is Inbound.Message -> delivered += deliver(inbound.link, inbound.bytes, sink)
                is Inbound.Left -> release(inbound.link)
            }
        }
        return delivered
    }

    override fun stats(peer: PeerId): TransportStats =
        statsByPeer.getOrPut(peer.raw) { TransportStats(peer) }

    override fun close() {
        if (closed) return
        closed = true
        for (slot in byPeer.indices) {
            val link = byPeer[slot] ?: continue
            byPeer[slot] = null
            link.outbox.close()
            listener.onDisconnected(PeerId(slot), DisconnectReason.LocalClosed)
        }
        server.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
        // Nobody polls a closed transport, so a connection coroutine still waiting to hand something
        // over is released with a cancellation rather than left suspended on a full queue.
        inbox.cancel()
    }

    private fun admit(link: Link, hello: ByteArray) {
        if (WebSocketLayout.typeOf(hello) != WebSocketMessageType.Hello || hello.size < WebSocketLayout.HELLO_BYTES) {
            counters.malformed++
            link.outbox.close()
            return
        }
        if (WebSocketLayout.unsignedShort(hello, WebSocketLayout.HELLO_PROTO_HASH) != protoHash) {
            deny(link, DisconnectReason.ProtocolMismatch)
            return
        }
        val slot = (1..config.maxClients).firstOrNull { byPeer[it] == null }
        if (slot == null) {
            deny(link, DisconnectReason.ServerFull)
            return
        }
        val peer = PeerId.client(slot)
        link.peer = peer
        byPeer[slot] = link
        link.outbox.trySend(WebSocketLayout.accepted(peer))
        counters.handshakesCompleted++
        listener.onConnected(peer)
    }

    private fun deny(link: Link, reason: DisconnectReason) {
        link.outbox.trySend(WebSocketLayout.denied(reason))
        link.outbox.close()
        counters.handshakesDenied++
    }

    private fun deliver(link: Link, message: ByteArray, sink: DatagramSink): Int {
        val peer = link.peer ?: return 0
        if (byPeer[peer.raw] !== link) return 0
        if (WebSocketLayout.typeOf(message) != WebSocketMessageType.Payload) {
            counters.malformed++
            return 0
        }
        val length = message.size - WebSocketLayout.PAYLOAD_BODY
        stats(peer).recordReceived(length)
        sink.receive(peer, message, WebSocketLayout.PAYLOAD_BODY, length)
        return 1
    }

    private fun release(link: Link) {
        link.outbox.close()
        val peer = link.peer ?: return
        if (byPeer[peer.raw] !== link) return
        byPeer[peer.raw] = null
        listener.onDisconnected(peer, DisconnectReason.RemoteClosed)
    }

    /** One connection's whole life, on Ktor's coroutine for it. */
    private suspend fun DefaultWebSocketServerSession.serve() {
        val link = Link(config.queueCapacity)
        val writer = launch {
            for (message in link.outbox) send(Frame.Binary(true, message))
            // The outbox closes when the game loop denies or releases this connection, or the server
            // closes; either way the socket goes with it.
            close()
        }
        var joined = false
        try {
            for (frame in incoming) {
                if (frame !is Frame.Binary) continue
                val bytes = frame.readBytes()
                inbox.send(if (joined) Inbound.Message(link, bytes) else Inbound.Joined(link, bytes))
                joined = true
            }
        } finally {
            // However the read ended - a clean close, a reset, the server stopping - the game loop
            // has to hear that this connection is gone, so this send must not be cancelled with it.
            if (!closed) withContext(NonCancellable) { inbox.send(Inbound.Left(link)) }
            writer.join()
        }
    }

    public companion object {

        /** The loopback address, for a server only this machine can reach. */
        public const val LOOPBACK: String = "127.0.0.1"

        /** The path a server serves its endpoint on unless told otherwise. */
        public const val DEFAULT_PATH: String = "/udea"

        private const val STOP_GRACE_MILLIS: Long = 100L
        private const val STOP_TIMEOUT_MILLIS: Long = 1_000L

        /**
         * Starts a server and returns once it is listening.
         *
         * @param host the address to listen on. [LOOPBACK] by default; `0.0.0.0` for every interface.
         * @param port the TCP port, or zero for an ephemeral one, readable afterwards from [port].
         * @param protoHash this build's [dev.wildware.udea.net.wire.ProtocolDescriptor.protoHash].
         *   A client whose hash differs is denied with [DisconnectReason.ProtocolMismatch].
         */
        public fun start(
            host: String = LOOPBACK,
            port: Int = 0,
            path: String = DEFAULT_PATH,
            protoHash: Int,
            config: WebSocketConfig = WebSocketConfig(),
            listener: UdpConnectionListener = UdpConnectionListener.NONE,
        ): WebSocketServerTransport = WebSocketServerTransport(protoHash, config, listener, host, port, path)
    }
}
