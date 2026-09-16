package dev.wildware.udea.net.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The HTTP engine a WebSocket client runs on here: CIO where there are operating-system sockets,
 * the platform's own `WebSocket` on Wasm.
 */
internal expect val webSocketEngine: HttpClientEngineFactory<HttpClientEngineConfig>

/**
 * A client's [Transport] over a WebSocket: the one transport every client platform has, and the
 * only one a browser has (issue #209, spec D11).
 *
 * ## Same SPI, same loop, different wire
 *
 * The replication stack above it does not know which transport it is on. [poll] hands over whole
 * messages the server sent, [send] queues one, and neither blocks - exactly as [UdpTransport]
 * behaves - so a `ReplicationClient` that runs over UDP on the desktop runs over this in a browser
 * unchanged. The difference is underneath: TCP delivers every message, once, in order, so a lost
 * or duplicated snapshot never happens here, and the replication layer's recovery simply has
 * nothing to recover from.
 *
 * ## Threads
 *
 * A coroutine of this transport's owns the connection: it writes what [send] queued and queues
 * what arrives. Everything a caller can observe - [isConnected], [localPeer], [failure], the
 * listener's callbacks - changes only inside [poll], on the caller's thread, so none of it is shared
 * with that coroutine and no step of the game loop sees it change underneath. On Wasm there is one
 * thread and the coroutine runs between frames; the rule is the same.
 *
 * ## The handshake
 *
 * The client's first message carries its protocol hash; the server answers with a peer id or a
 * [DisconnectReason] and closes. There is no token and no challenge, because the address-ownership
 * proof they exist for on UDP is what a TCP connection already is.
 */
public class WebSocketTransport private constructor(
    private val url: String,
    private val protoHash: Int,
    private val config: WebSocketConfig,
    private val listener: UdpConnectionListener,
) : Transport, AutoCloseable {

    /** What the connection's coroutine hands to [poll]. */
    private sealed interface Inbound {
        class Message(val bytes: ByteArray) : Inbound
        class Ended(val reason: DisconnectReason, val cause: Throwable?) : Inbound
    }

    private val inbox = Channel<Inbound>(config.queueCapacity)
    private val outbox = Channel<ByteArray>(config.queueCapacity)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient(webSocketEngine) { install(WebSockets) }
    private val serverStats = TransportStats(PeerId.SERVER)
    private val otherStats = HashMap<Int, TransportStats>()
    private var connected = false
    private var closed = false

    /** Every refusal, by why. Live, like [TransportStats]. */
    public val counters: WebSocketCounters = WebSocketCounters()

    /** The peer id the server assigned, or an unassigned id before it has. */
    override var localPeer: PeerId = PeerId(UNASSIGNED_RAW)
        private set

    /** Whether the server has accepted this client and the connection is still open. */
    public val isConnected: Boolean get() = connected

    /** Why the connection ended or never opened, or null while it is opening or open. */
    public var failure: DisconnectReason? = null
        private set

    /**
     * What the connection failed with, when it failed rather than closing: the refused connect or
     * the broken read. Null otherwise. Kept for the person reading [failure], who needs to know
     * whether "unreachable" was a wrong port or a certificate.
     */
    public var failureCause: Throwable? = null
        private set

    init {
        require(protoHash in 0..PROTO_HASH_MAX) { "protoHash must fit 16 bits, was 0x${protoHash.toString(16)}" }
        io.launch { connect() }
    }

    override fun send(peer: PeerId, bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "$localPeer transport is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "slice [$offset, ${offset + length}) does not fit a ${bytes.size} byte buffer"
        }
        if (length > config.maxMessageBytes) throw DatagramTooLargeException(length, config.maxMessageBytes)
        if (peer != PeerId.SERVER || !connected) {
            counters.sendsToUnknownPeer++
            return
        }
        if (outbox.trySend(WebSocketLayout.payload(bytes, offset, length)).isSuccess) {
            serverStats.recordSent(length)
        } else {
            serverStats.packetsDropped++
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
                is Inbound.Message -> delivered += onMessage(inbound.bytes, sink)
                is Inbound.Ended -> end(inbound.reason, inbound.cause)
            }
        }
        return delivered
    }

    override fun stats(peer: PeerId): TransportStats =
        if (peer == PeerId.SERVER) serverStats else otherStats.getOrPut(peer.raw) { TransportStats(peer) }

    override fun close() {
        if (closed) return
        closed = true
        outbox.close()
        io.cancel()
        http.close()
        if (connected) {
            connected = false
            listener.onDisconnected(PeerId.SERVER, DisconnectReason.LocalClosed)
        }
    }

    private fun onMessage(message: ByteArray, sink: DatagramSink): Int {
        when (WebSocketLayout.typeOf(message)) {
            WebSocketMessageType.Payload -> if (connected) {
                val length = message.size - WebSocketLayout.PAYLOAD_BODY
                serverStats.recordReceived(length)
                sink.receive(PeerId.SERVER, message, WebSocketLayout.PAYLOAD_BODY, length)
                return 1
            }
            WebSocketMessageType.Accepted -> if (!connected && failure == null && message.size >= WebSocketLayout.ACCEPTED_BYTES) {
                val raw = WebSocketLayout.unsignedShort(message, WebSocketLayout.ACCEPTED_PEER)
                if (raw >= 1) {
                    localPeer = PeerId(raw)
                    connected = true
                    counters.handshakesCompleted++
                    listener.onConnected(PeerId.SERVER)
                    return 0
                }
            }
            WebSocketMessageType.Denied -> if (!connected && failure == null && message.size >= WebSocketLayout.DENIED_BYTES) {
                counters.handshakesDenied++
                end(DisconnectReason.of(message[WebSocketLayout.DENIED_REASON].toInt() and 0xFF), cause = null)
                return 0
            }
            WebSocketMessageType.Hello, null -> Unit
        }
        counters.malformed++
        return 0
    }

    /** Records why the connection is over, once: a denial and the close that follows it are one ending. */
    private fun end(reason: DisconnectReason, cause: Throwable?) {
        if (failure != null) return
        connected = false
        failure = reason
        failureCause = cause
        listener.onDisconnected(PeerId.SERVER, reason)
    }

    /** The connection's whole life, on [io]. */
    private suspend fun connect() {
        var opened = false
        val ending: Inbound.Ended = try {
            http.webSocket(url) {
                opened = true
                send(Frame.Binary(true, WebSocketLayout.hello(protoHash)))
                val writer = launch { for (message in outbox) send(Frame.Binary(true, message)) }
                for (frame in incoming) {
                    if (frame is Frame.Binary) inbox.send(Inbound.Message(frame.readBytes()))
                }
                writer.cancel()
            }
            Inbound.Ended(DisconnectReason.RemoteClosed, cause = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Not swallowed: the error becomes this transport's `failure` and `failureCause` at the
            // next poll, and the listener is told. A connect that never opened is Unreachable; one
            // that broke after opening is the server going away.
            Inbound.Ended(if (opened) DisconnectReason.RemoteClosed else DisconnectReason.Unreachable, error)
        }
        inbox.send(ending)
    }

    public companion object {

        /** Largest value the sixteen-bit protocol hash field can carry. */
        private const val PROTO_HASH_MAX: Int = 0xFFFF

        /** A client's peer id before the server has assigned one. */
        private const val UNASSIGNED_RAW: Int = -1

        /**
         * Opens a connection to [url] and begins the handshake.
         *
         * Returns at once. The connection opens on a coroutine, and [isConnected] turns true inside
         * a later [poll], or [failure] is set there if it never does.
         *
         * @param url a `ws://` or `wss://` URL, such as `ws://127.0.0.1:7777/udea`.
         * @param protoHash this build's [dev.wildware.udea.net.wire.ProtocolDescriptor.protoHash].
         */
        public fun client(
            url: String,
            protoHash: Int,
            config: WebSocketConfig = WebSocketConfig(),
            listener: UdpConnectionListener = UdpConnectionListener.NONE,
        ): WebSocketTransport = WebSocketTransport(url, protoHash, config, listener)
    }
}
