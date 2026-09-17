package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.net.wire.ProtocolDescriptor
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray

/** Where a client is in the three-way connect exchange. */
internal enum class HandshakeState {

    /** Sending [UdpPacketType.ConnectionRequest] until a challenge comes back. */
    Requesting,

    /** Sending [UdpPacketType.ConnectionResponse] until an accept comes back. */
    Responding,

    /** The connection is open. */
    Connected,

    /** Refused or timed out. This transport will not connect. */
    Failed,
}

/**
 * The [Transport] that actually opens a socket (issue #113, spec 3.4 and 5).
 *
 * ## What it is, in one clause
 *
 * A Ktor datagram socket behind the same four-method SPI as [LoopbackTransport], so the
 * replication stack that was written and tested against an in-memory queue runs over the
 * internet without knowing it moved. Ktor rather than `java.nio` since issue #209, so the same
 * source serves every target with operating-system sockets (spec section 6).
 *
 * ## The game loop drives it, and it reads no wall clock
 *
 * Both properties are load-bearing rather than stylistic. Every decision - handshake, token,
 * replay window, timeout, keep-alive - is taken on the thread that calls [flush], [poll] and
 * [send], in that order, exactly as [NetHarness] does, so none of that state is shared with
 * anything. The one thing another thread does is read the socket: Ktor delivers datagrams on a
 * coroutine, and a coroutine of this transport's copies each into a bounded queue that [poll]
 * drains, which is spec section 6's "network I/O runs on coroutines and delivers into a queue".
 * A full queue stops the reader, the kernel's socket buffer fills behind it and the kernel drops
 * what does not fit - the back pressure a UDP socket has always had. Sends are written to the
 * socket inside [send] itself. No wall clock means every timeout, retry and keep-alive is a
 * [Tick] read from the injected [ManualClock], which is what lets `NoWallClockInTransportTest`
 * scan this file and what makes the whole package's failures reproducible. The clock is advanced
 * by whatever paces the game loop; this class never advances it.
 *
 * ## The layering, and the header this adds
 *
 * [send] takes opaque bytes and puts an 18-byte header in front of them: a connection salt, a
 * sequence, an acknowledgement and its bitfield, and a fragment flag. That is deliberately a
 * *different* sequence space from [PacketHeader]'s — see [UdpLayout] for why one cannot serve
 * both. Nothing here parses a snapshot, and there is no path from a received datagram to a
 * component field, which is the structural half of "clients send input, never state".
 *
 * ## Public-internet hardening, in the first version of the format
 *
 * Decision D10, because spec section 7 says retrofitting it is a wire-format break:
 *
 * - **Connection tokens.** A three-way handshake in which the server keeps no state until the
 *   peer returns a token minted for its own address ([ConnectionSecret]).
 * - **Anti-amplification.** A reply to an unverified peer is never larger than what arrived
 *   ([AmplificationGuard]); both client-to-server handshake datagrams are padded to the MTU to
 *   make room for the replies they prompt.
 * - **Rate limiting.** Per source address and globally per tick ([HandshakeRateLimiter]).
 * - **Replay refusal.** A payload whose sequence is already in the window is dropped
 *   ([UdpConnection]).
 * - **Bounded reassembly.** A fixed number of expiring assemblies per peer
 *   ([FragmentReassembler]).
 *
 * ## What it deliberately does not do
 *
 * It never retransmits a payload — see [UdpConnection.rtoTicks] for why a stale delta is worse
 * than the fresh one that is about to be produced anyway. It does not encrypt: the token
 * proves address ownership, not confidentiality, and an encrypted transport is a separate
 * decision with its own key management.
 */
public class UdpTransport private constructor(
    private val selector: SelectorManager,
    private val socket: BoundDatagramSocket,
    private val clock: ManualClock,
    private val config: UdpConfig,

    /** Non-null on a server, which mints tokens; null on a client, which only echoes them. */
    private val secret: ConnectionSecret?,

    /** [dev.wildware.udea.net.wire.ProtocolDescriptor.protoHash] of this build. */
    private val protoHash: Int,

    /** Null on a server. On a client, the only address datagrams are accepted from. */
    private val serverAddress: InetSocketAddress?,

    /** The client's opening nonce. Unused on a server. */
    private val clientSalt: Long,

    private val listener: UdpConnectionListener,
) : Transport, AutoCloseable {

    init {
        // The header carries the hash in sixteen bits, which is what `ProtocolDescriptor` folds
        // it to. A caller handing over a wider value would have it truncated on the way out and
        // compared truncated on the way in, so two genuinely different protocols could agree.
        require(protoHash in 0..PROTO_HASH_MAX) {
            "protoHash must fit ${ProtocolDescriptor.PROTO_HASH_BITS} bits, " +
                "was 0x${protoHash.toString(16)}"
        }
    }

    private val isServer: Boolean = serverAddress == null

    private val sendBuffer = ByteArray(config.mtu)

    /** One datagram off the socket, and where it came from. */
    private class Arrival(val source: InetSocketAddress, val bytes: ByteArray)

    /**
     * What the reader has taken off the socket and [poll] has not yet handled.
     *
     * Bounded at [UdpConfig.maxReceivesPerPoll]: one poll's worth. See the class KDoc for what
     * happens beyond it.
     */
    private val inbox = Channel<Arrival>(config.maxReceivesPerPoll)

    /**
     * The datagram [handle] is reading, whole.
     *
     * Ktor hands over each datagram at its real length, up to UDP's 65,535 bytes, so an oversized
     * one arrives as what it is and is refused by length. Before issue #209 a receive buffer had
     * to be deliberately larger than the MTU to make that true.
     */
    private var received: ByteArray = EMPTY

    /** Owns the reader. Cancelled by [close]. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val arrivalLock = SynchronizedObject()

    /** Datagrams the reader has taken off the socket, ever. Guarded by [arrivalLock]. */
    private var arrivals: Long = 0L

    /**
     * Datagrams the reader has taken off the socket since this transport opened.
     *
     * The one number that crosses from the reader to the game loop's thread, so a test driving two
     * real sockets can wait for what one side sent to have arrived at the other before it steps,
     * rather than guessing with a sleep.
     */
    internal val datagramsArrived: Long
        get() = synchronized(arrivalLock) { arrivals }

    /** Datagrams handed to the socket since this transport opened. */
    internal var datagramsTransmitted: Long = 0L
        private set

    init {
        io.launch {
            for (datagram in socket.incoming) {
                val bytes = datagram.packet.readByteArray()
                inbox.send(Arrival(datagram.address as InetSocketAddress, bytes))
                synchronized(arrivalLock) { arrivals++ }
            }
        }
    }

    private val byPeer = arrayOfNulls<UdpConnection>(config.maxClients + 1)
    private val byAddress = LinkedHashMap<InetSocketAddress, UdpConnection>()
    private val statsByPeer = HashMap<Int, TransportStats>()

    private val rateLimiter = HandshakeRateLimiter()
    private val amplification = AmplificationGuard()

    private var assignedPeer: PeerId = if (isServer) PeerId.SERVER else PeerId(UNASSIGNED_RAW)
    private var handshakeState: HandshakeState = if (isServer) HandshakeState.Connected else HandshakeState.Requesting
    private var handshakeDeadline: Tick = clock.tick + config.connectTimeoutTicks
    private var nextHandshakeSendAt: Tick = clock.tick
    private var handshakeAttempts: Int = 0
    private var challengeToken: Long = 0L
    private var challengeExpiry: Tick = Tick.ZERO
    private var closed = false

    /** Fragment counters of connections that have since gone away. */
    private var retiredFragmentTimeouts: Long = 0L
    private var retiredFragmentRefusals: Long = 0L

    /** Every refusal, by why. Live, like [TransportStats]. */
    public val counters: UdpCounters = UdpCounters()

    /** Body bytes an unfragmented payload carries. */
    private val wholeBodyBytes: Int = config.mtu - UdpLayout.PAYLOAD_HEADER_BYTES

    /** Body bytes one fragment carries. */
    private val fragmentBodyBytes: Int = config.mtu - UdpLayout.FRAGMENT_HEADER_BYTES

    override val localPeer: PeerId get() = assignedPeer

    /** The address the socket is actually bound to, including an ephemeral port. */
    public val localAddress: InetSocketAddress = socket.localAddress as InetSocketAddress

    /** Whether this end has a live connection. On a server, whether it has any. */
    public val isConnected: Boolean
        get() = if (isServer) byAddress.isNotEmpty() else handshakeState == HandshakeState.Connected

    /** Why a client's handshake ended, or null while it is still running or succeeded. */
    public var failure: DisconnectReason? = null
        private set

    /** Largest message [send] will accept, after which it throws. */
    public val maxMessageBytes: Int
        get() = maxOf(wholeBodyBytes, FragmentReassembler.DEFAULT_MAX_FRAGMENTS * fragmentBodyBytes)

    /** Every live connection, ascending by peer id. */
    public fun connections(): List<PeerId> = byPeer.filterNotNull().map(UdpConnection::peer)

    /** Smoothed round trip to [peer] in ticks, or negative before the first sample. */
    public fun rttTicks(peer: PeerId): Float = connectionFor(peer)?.smoothedRttTicks ?: -1f

    /** Current retransmit timeout for [peer], in ticks. */
    public fun rtoTicks(peer: PeerId): Long = connectionFor(peer)?.rtoTicks ?: config.minRtoTicks

    /**
     * The tick hook: retries, keep-alives, timeouts and expiries.
     *
     * Named to match [SimulatedTransport.flush] because it occupies the same slot in the loop —
     * after the clock advances and before [poll] — so one driver can step either transport. The
     * SPI has no room for it because [LoopbackTransport] needs none.
     */
    public fun flush() {
        if (closed) return
        val now = clock.tick
        if (!isServer) advanceHandshake(now)
        for (index in byPeer.indices) {
            val connection = byPeer[index] ?: continue
            connection.reassembler.expire(now)
            if (connection.isTimedOut(now, config.timeoutTicks)) {
                retire(connection, DisconnectReason.Timeout)
                continue
            }
            if (connection.needsKeepAlive(now, config.keepAliveIntervalTicks)) {
                sendPayload(connection, EMPTY, 0, 0, now)
            }
        }
        refreshCounters()
    }

    override fun send(peer: PeerId, bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "$localPeer transport is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "slice [$offset, ${offset + length}) does not fit a ${bytes.size} byte buffer"
        }
        if (length > maxMessageBytes) throw DatagramTooLargeException(length, maxMessageBytes)
        val connection = connectionFor(peer)
        if (connection == null) {
            counters.sendsToUnknownPeer++
            return
        }
        val now = clock.tick
        if (length <= wholeBodyBytes) {
            sendPayload(connection, bytes, offset, length, now)
            return
        }
        val count = (length + fragmentBodyBytes - 1) / fragmentBodyBytes
        val messageId = connection.nextMessageId()
        for (index in 0 until count) {
            val start = offset + index * fragmentBodyBytes
            val size = minOf(fragmentBodyBytes, offset + length - start)
            sendFragment(connection, messageId, index, count, bytes, start, size, now)
        }
    }

    override fun poll(sink: DatagramSink): Int {
        if (closed) return 0
        var delivered = 0
        var taken = 0
        while (taken < config.maxReceivesPerPoll) {
            val arrival = inbox.tryReceive().getOrNull() ?: break
            taken++
            received = arrival.bytes
            delivered += handle(arrival.source, arrival.bytes.size, sink)
        }
        return delivered
    }

    override fun stats(peer: PeerId): TransportStats =
        statsByPeer.getOrPut(peer.raw) { TransportStats(peer) }

    override fun close() {
        if (closed) return
        closed = true
        for (index in byPeer.indices) {
            val connection = byPeer[index] ?: continue
            sendBuffer[UdpLayout.TYPE] = UdpPacketType.Disconnect.id.toByte()
            BigEndian.putLong(sendBuffer, UdpLayout.DISCONNECT_SALT, connection.salt)
            repeat(config.disconnectSends) { transmit(connection.address, UdpLayout.DISCONNECT_BYTES) }
            connection.reassembler.clear()
            byPeer[index] = null
            listener.onDisconnected(connection.peer, DisconnectReason.LocalClosed)
        }
        byAddress.clear()
        io.cancel()
        socket.close()
        selector.close()
    }

    // --- receive dispatch -------------------------------------------------------------------

    private fun handle(source: InetSocketAddress, length: Int, sink: DatagramSink): Int {
        if (length < 1) {
            counters.malformed++
            return 0
        }
        if (length > config.mtu) {
            counters.oversized++
            return 0
        }
        val type = UdpPacketType.of(received[UdpLayout.TYPE].toInt() and 0xFF)
        if (type == null) {
            counters.malformed++
            return 0
        }
        return when (type) {
            UdpPacketType.Payload -> onPayload(source, length, sink)
            UdpPacketType.Disconnect -> onDisconnect(source, length)
            UdpPacketType.ConnectionRequest -> onConnectionRequest(source, length)
            UdpPacketType.ConnectionResponse -> onConnectionResponse(source, length)
            UdpPacketType.ConnectionChallenge -> onConnectionChallenge(source, length)
            UdpPacketType.ConnectionAccepted -> onConnectionAccepted(source, length)
            UdpPacketType.ConnectionDenied -> onConnectionDenied(source, length)
        }
    }

    private fun onPayload(source: InetSocketAddress, length: Int, sink: DatagramSink): Int {
        if (length < UdpLayout.PAYLOAD_HEADER_BYTES) {
            counters.malformed++
            return 0
        }
        val connection = byAddress[source]
        if (connection == null || connection.salt != BigEndian.getLong(received, UdpLayout.PAYLOAD_SALT)) {
            // Either nobody here has that address, or somebody with that address does not know
            // the salt. Both are refused with no reply at all: replying would tell a scanner
            // that something is listening.
            counters.unknownConnection++
            return 0
        }
        val now = clock.tick
        if (!connection.onReceived(unsignedShort(UdpLayout.PAYLOAD_SEQ), now)) {
            counters.replayed++
            connection.stats.packetsDropped++
            return 0
        }
        connection.onAck(unsignedShort(UdpLayout.PAYLOAD_ACK), BigEndian.getInt(received, UdpLayout.PAYLOAD_ACK_BITS), now)
        connection.stats.recordReceived(length)
        if (received[UdpLayout.PAYLOAD_FRAGMENT_FLAG].toInt() != UdpLayout.FRAGMENT_FLAG_SET) {
            val body = length - UdpLayout.PAYLOAD_HEADER_BYTES
            if (body == 0) return 0
            sink.receive(connection.peer, received, UdpLayout.PAYLOAD_HEADER_BYTES, body)
            return 1
        }
        if (length < UdpLayout.FRAGMENT_HEADER_BYTES) {
            counters.malformed++
            return 0
        }
        val assembly = connection.reassembler.accept(
            messageId = unsignedShort(UdpLayout.FRAGMENT_MESSAGE_ID),
            index = received[UdpLayout.FRAGMENT_INDEX].toInt() and 0xFF,
            count = received[UdpLayout.FRAGMENT_COUNT].toInt() and 0xFF,
            source = received,
            offset = UdpLayout.FRAGMENT_HEADER_BYTES,
            length = length - UdpLayout.FRAGMENT_HEADER_BYTES,
            now = now,
        ) ?: return 0
        sink.receive(connection.peer, assembly.payload, 0, assembly.totalBytes)
        connection.reassembler.release(assembly)
        return 1
    }

    private fun onDisconnect(source: InetSocketAddress, length: Int): Int {
        if (length < UdpLayout.DISCONNECT_BYTES) {
            counters.malformed++
            return 0
        }
        // A goodbye for a connection that is already gone is not an anomaly, it is this
        // transport's own doing: the notice is unreliable, so it goes out `disconnectSends`
        // times, and every copy after the first necessarily arrives after the connection was
        // retired. Counting those would put a guaranteed non-zero number in a counter whose
        // whole value is being zero when nothing is wrong.
        val connection = byAddress[source] ?: return 0
        if (connection.salt != BigEndian.getLong(received, UdpLayout.DISCONNECT_SALT)) {
            // A live connection at this address, and something that does not know its salt is
            // asking for it to be closed. That is somebody trying to kick a player.
            counters.unknownConnection++
            return 0
        }
        retire(connection, DisconnectReason.RemoteClosed)
        return 0
    }

    // --- server side of the handshake -------------------------------------------------------

    private fun onConnectionRequest(source: InetSocketAddress, length: Int): Int {
        if (!isServer) {
            counters.malformed++
            return 0
        }
        // An unpadded request is refused before anything else: the padding is what pays for the
        // challenge, and accepting a short one would be accepting an amplifier.
        if (length != config.mtu) {
            counters.malformed++
            return 0
        }
        if (!rateLimiter.allow(source, clock.tick)) {
            counters.rateLimited++
            return 0
        }
        if (unsignedShort(UdpLayout.REQUEST_PROTO_HASH) != protoHash) {
            // Denied here rather than silently, even though the peer is unverified: the reply is
            // two bytes against 1200 received, so it cannot amplify, and a build mismatch that
            // presents as a silent timeout is the failure the old stack could never explain.
            deny(source, DisconnectReason.ProtocolMismatch, length)
            return 0
        }
        val salt = BigEndian.getLong(received, UdpLayout.REQUEST_CLIENT_SALT)
        val expiry = clock.tick + config.tokenLifetimeTicks
        val token = requireSecret().token(source, salt, expiry)
        sendBuffer[UdpLayout.TYPE] = UdpPacketType.ConnectionChallenge.id.toByte()
        BigEndian.putLong(sendBuffer, UdpLayout.CHALLENGE_CLIENT_SALT, salt)
        BigEndian.putLong(sendBuffer, UdpLayout.CHALLENGE_TOKEN, token)
        BigEndian.putLong(sendBuffer, UdpLayout.CHALLENGE_EXPIRY, expiry.value)
        transmitUnverified(source, UdpLayout.CHALLENGE_BYTES, length)
        return 0
    }

    private fun onConnectionResponse(source: InetSocketAddress, length: Int): Int {
        if (!isServer) {
            counters.malformed++
            return 0
        }
        if (length != config.mtu) {
            counters.malformed++
            return 0
        }
        if (!rateLimiter.allow(source, clock.tick)) {
            counters.rateLimited++
            return 0
        }
        if (unsignedShort(UdpLayout.RESPONSE_PROTO_HASH) != protoHash) {
            deny(source, DisconnectReason.ProtocolMismatch, length)
            return 0
        }
        val salt = BigEndian.getLong(received, UdpLayout.RESPONSE_CLIENT_SALT)
        val token = BigEndian.getLong(received, UdpLayout.RESPONSE_TOKEN)
        val expiry = Tick(BigEndian.getLong(received, UdpLayout.RESPONSE_EXPIRY))
        val existing = byAddress[source]
        if (existing != null) {
            // The accept was lost and the client is still asking. Re-send it; do not mint a
            // second connection, which is how a replayed response becomes a slot leak.
            if (existing.salt == (salt xor token)) accept(existing, length) else counters.malformed++
            return 0
        }
        val now = clock.tick
        if (expiry < now) {
            counters.tokenExpired++
            return 0
        }
        if (expiry.ticksSince(now) > config.tokenLifetimeTicks) {
            // A token further in the future than this server ever mints was not minted here.
            counters.malformed++
            return 0
        }
        if (!requireSecret().verifies(source, salt, expiry, token)) {
            // Silent. A reply would turn the server into an oracle a forger can grind against.
            counters.tokenRejected++
            return 0
        }
        val slot = freeSlot()
        if (slot == NO_SLOT) {
            deny(source, DisconnectReason.ServerFull, length)
            return 0
        }
        val peer = PeerId.client(slot)
        val connection = UdpConnection(
            peer = peer,
            address = source,
            salt = salt xor token,
            stats = stats(peer),
            fragmentBytes = fragmentBodyBytes,
            createdAt = now,
            minRtoTicks = config.minRtoTicks,
            maxRtoTicks = config.maxRtoTicks,
        )
        byPeer[slot] = connection
        byAddress[source] = connection
        counters.handshakesCompleted++
        accept(connection, length)
        listener.onConnected(peer)
        return 0
    }

    private fun accept(connection: UdpConnection, receivedBytes: Int) {
        sendBuffer[UdpLayout.TYPE] = UdpPacketType.ConnectionAccepted.id.toByte()
        BigEndian.putLong(sendBuffer, UdpLayout.ACCEPTED_SALT, connection.salt)
        BigEndian.putShort(sendBuffer, UdpLayout.ACCEPTED_PEER, connection.peer.raw.toShort())
        transmitUnverified(connection.address, UdpLayout.ACCEPTED_BYTES, receivedBytes)
    }

    private fun deny(source: InetSocketAddress, reason: DisconnectReason, receivedBytes: Int) {
        sendBuffer[UdpLayout.TYPE] = UdpPacketType.ConnectionDenied.id.toByte()
        sendBuffer[UdpLayout.DENIED_REASON] = reason.id.toByte()
        if (transmitUnverified(source, UdpLayout.DENIED_BYTES, receivedBytes)) counters.handshakesDenied++
    }

    // --- client side of the handshake -------------------------------------------------------

    private fun onConnectionChallenge(source: InetSocketAddress, length: Int): Int {
        if (isServer || source != serverAddress || handshakeState != HandshakeState.Requesting) {
            counters.malformed++
            return 0
        }
        if (length < UdpLayout.CHALLENGE_BYTES ||
            BigEndian.getLong(received, UdpLayout.CHALLENGE_CLIENT_SALT) != clientSalt
        ) {
            counters.malformed++
            return 0
        }
        challengeToken = BigEndian.getLong(received, UdpLayout.CHALLENGE_TOKEN)
        challengeExpiry = Tick(BigEndian.getLong(received, UdpLayout.CHALLENGE_EXPIRY))
        handshakeState = HandshakeState.Responding
        handshakeAttempts = 0
        sendHandshake(clock.tick)
        return 0
    }

    private fun onConnectionAccepted(source: InetSocketAddress, length: Int): Int {
        if (isServer || source != serverAddress || length < UdpLayout.ACCEPTED_BYTES) {
            counters.malformed++
            return 0
        }
        if (handshakeState == HandshakeState.Connected) return 0
        if (handshakeState != HandshakeState.Responding) {
            counters.malformed++
            return 0
        }
        val salt = BigEndian.getLong(received, UdpLayout.ACCEPTED_SALT)
        if (salt != (clientSalt xor challengeToken)) {
            // Anyone can send an accept; only the server that minted the token knows the salt
            // this client is expecting.
            counters.malformed++
            return 0
        }
        val raw = unsignedShort(UdpLayout.ACCEPTED_PEER)
        if (raw < 1) {
            counters.malformed++
            return 0
        }
        val now = clock.tick
        assignedPeer = PeerId(raw)
        val connection = UdpConnection(
            peer = PeerId.SERVER,
            address = source,
            salt = salt,
            stats = stats(PeerId.SERVER),
            fragmentBytes = fragmentBodyBytes,
            createdAt = now,
            minRtoTicks = config.minRtoTicks,
            maxRtoTicks = config.maxRtoTicks,
        )
        byPeer[SERVER_SLOT] = connection
        byAddress[source] = connection
        handshakeState = HandshakeState.Connected
        counters.handshakesCompleted++
        listener.onConnected(PeerId.SERVER)
        return 0
    }

    private fun onConnectionDenied(source: InetSocketAddress, length: Int): Int {
        if (isServer || source != serverAddress || length < UdpLayout.DENIED_BYTES) {
            counters.malformed++
            return 0
        }
        if (handshakeState == HandshakeState.Connected) return 0
        val reason = DisconnectReason.of(received[UdpLayout.DENIED_REASON].toInt() and 0xFF)
        handshakeState = HandshakeState.Failed
        failure = reason
        counters.handshakesDenied++
        listener.onDisconnected(PeerId.SERVER, reason)
        return 0
    }

    private fun advanceHandshake(now: Tick) {
        if (handshakeState == HandshakeState.Connected || handshakeState == HandshakeState.Failed) return
        if (now >= handshakeDeadline) {
            handshakeState = HandshakeState.Failed
            failure = DisconnectReason.HandshakeTimeout
            listener.onDisconnected(PeerId.SERVER, DisconnectReason.HandshakeTimeout)
            return
        }
        if (now >= nextHandshakeSendAt) sendHandshake(now)
    }

    /**
     * Sends whichever handshake datagram this client currently owes, and schedules the retry.
     *
     * The backoff doubles per attempt from the connection's RTO floor, which is the only place
     * an RTO drives a retransmission in this transport. Without the doubling, a client behind a
     * black hole sends at a fixed rate for the whole connect timeout and looks like the flood
     * the server's rate limiter exists to stop.
     */
    private fun sendHandshake(now: Tick) {
        val bytes = when (handshakeState) {
            HandshakeState.Requesting -> {
                sendBuffer[UdpLayout.TYPE] = UdpPacketType.ConnectionRequest.id.toByte()
                BigEndian.putShort(sendBuffer, UdpLayout.REQUEST_PROTO_HASH, protoHash.toShort())
                BigEndian.putLong(sendBuffer, UdpLayout.REQUEST_CLIENT_SALT, clientSalt)
                pad(UdpLayout.REQUEST_BODY_BYTES)
            }
            HandshakeState.Responding -> {
                sendBuffer[UdpLayout.TYPE] = UdpPacketType.ConnectionResponse.id.toByte()
                BigEndian.putShort(sendBuffer, UdpLayout.RESPONSE_PROTO_HASH, protoHash.toShort())
                BigEndian.putLong(sendBuffer, UdpLayout.RESPONSE_CLIENT_SALT, clientSalt)
                BigEndian.putLong(sendBuffer, UdpLayout.RESPONSE_TOKEN, challengeToken)
                BigEndian.putLong(sendBuffer, UdpLayout.RESPONSE_EXPIRY, challengeExpiry.value)
                pad(UdpLayout.RESPONSE_BODY_BYTES)
            }
            else -> return
        }
        transmit(checkNotNull(serverAddress) { "a server does not send handshake datagrams" }, bytes)
        val backoff = config.minRtoTicks shl minOf(handshakeAttempts, MAX_BACKOFF_DOUBLINGS)
        handshakeAttempts++
        nextHandshakeSendAt = now + minOf(backoff, config.maxRtoTicks)
    }

    /**
     * Zeroes the padding out to the MTU and returns the datagram length.
     *
     * Zeroed rather than left as whatever the buffer held, because the buffer is shared with
     * payload sends and leaking a previous snapshot's bytes to an unauthenticated peer would be
     * an information leak in the one place there is no connection to leak it to.
     */
    private fun pad(bodyBytes: Int): Int {
        sendBuffer.fill(0, bodyBytes, config.mtu)
        return config.mtu
    }

    // --- send path --------------------------------------------------------------------------

    private fun sendPayload(
        connection: UdpConnection,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        now: Tick,
    ) {
        writePayloadHeader(connection, now, fragmented = false)
        bytes.copyInto(sendBuffer, UdpLayout.PAYLOAD_HEADER_BYTES, offset, offset + length)
        val total = UdpLayout.PAYLOAD_HEADER_BYTES + length
        if (transmit(connection.address, total)) connection.stats.recordSent(total)
    }

    private fun sendFragment(
        connection: UdpConnection,
        messageId: Int,
        index: Int,
        count: Int,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        now: Tick,
    ) {
        writePayloadHeader(connection, now, fragmented = true)
        BigEndian.putShort(sendBuffer, UdpLayout.FRAGMENT_MESSAGE_ID, messageId.toShort())
        sendBuffer[UdpLayout.FRAGMENT_INDEX] = index.toByte()
        sendBuffer[UdpLayout.FRAGMENT_COUNT] = count.toByte()
        bytes.copyInto(sendBuffer, UdpLayout.FRAGMENT_HEADER_BYTES, offset, offset + length)
        val total = UdpLayout.FRAGMENT_HEADER_BYTES + length
        if (transmit(connection.address, total)) connection.stats.recordSent(total)
    }

    private fun writePayloadHeader(connection: UdpConnection, now: Tick, fragmented: Boolean) {
        sendBuffer[UdpLayout.TYPE] = UdpPacketType.Payload.id.toByte()
        BigEndian.putLong(sendBuffer, UdpLayout.PAYLOAD_SALT, connection.salt)
        BigEndian.putShort(sendBuffer, UdpLayout.PAYLOAD_SEQ, connection.beginSend(now).toShort())
        val ack = if (connection.remoteSeq == UdpConnection.NO_SEQ) 0 else connection.remoteSeq
        BigEndian.putShort(sendBuffer, UdpLayout.PAYLOAD_ACK, (ack and PacketHeader.SEQ_MASK).toShort())
        BigEndian.putInt(sendBuffer, UdpLayout.PAYLOAD_ACK_BITS, connection.remoteAckBits)
        sendBuffer[UdpLayout.PAYLOAD_FRAGMENT_FLAG] = (if (fragmented) UdpLayout.FRAGMENT_FLAG_SET else 0).toByte()
    }

    /** Sends [length] bytes of [sendBuffer], subject to the anti-amplification rule. */
    private fun transmitUnverified(target: InetSocketAddress, length: Int, receivedBytes: Int): Boolean {
        if (!amplification.permits(length, receivedBytes)) {
            counters.amplificationBlocked++
            return false
        }
        return transmit(target, length)
    }

    private fun transmit(target: InetSocketAddress, length: Int): Boolean {
        val packet = Buffer()
        packet.write(sendBuffer, 0, length)
        val sent = try {
            // Non-suspending: Ktor writes to the socket inside `trySend` when the kernel will take
            // the datagram, and drops it otherwise, which this transport may do at any time.
            socket.outgoing.trySend(Datagram(packet, target)).isSuccess
        } catch (error: IOException) {
            // On Windows an ICMP port-unreachable from a peer that died surfaces as a failed write
            // on a perfectly healthy socket, and a saturated link refuses one with ENOBUFS. Either
            // is a dropped datagram, counted below; neither is a reason to stop serving the other
            // peers, which is one of the cases this transport exists to survive.
            false
        }
        if (sent) datagramsTransmitted++ else counters.receiveErrors++
        return sent
    }

    // --- housekeeping -----------------------------------------------------------------------

    private fun retire(connection: UdpConnection, reason: DisconnectReason) {
        retiredFragmentTimeouts += connection.reassembler.timedOut
        retiredFragmentRefusals += connection.reassembler.refused
        connection.reassembler.clear()
        byAddress.remove(connection.address)
        for (index in byPeer.indices) if (byPeer[index] === connection) byPeer[index] = null
        if (!isServer) {
            handshakeState = HandshakeState.Failed
            failure = reason
        }
        listener.onDisconnected(connection.peer, reason)
    }

    private fun refreshCounters() {
        var timeouts = retiredFragmentTimeouts
        var refusals = retiredFragmentRefusals
        for (connection in byPeer) {
            if (connection == null) continue
            timeouts += connection.reassembler.timedOut
            refusals += connection.reassembler.refused
        }
        // Only the fragment counters are recomputed. `rateLimited` and `amplificationBlocked`
        // are incremented at the point of refusal instead, because a counter written in two
        // places is two places that can disagree, and those two are read by tests the moment
        // the refusal happens rather than a tick later.
        counters.fragmentsTimedOut = timeouts
        counters.fragmentsRefused = refusals
    }

    /**
     * The live connection to [peer].
     *
     * The seam the hostile-case tests forge datagrams against: a replay or a salt-guess test has
     * to be able to build a datagram this transport would otherwise accept, and a test that
     * cannot build one is a test that cannot fail.
     */
    internal fun connectionOf(peer: PeerId): UdpConnection? = connectionFor(peer)

    private fun connectionFor(peer: PeerId): UdpConnection? {
        if (!isServer) return if (peer == PeerId.SERVER) byPeer[SERVER_SLOT] else null
        return if (peer.raw in 1..config.maxClients) byPeer[peer.raw] else null
    }

    private fun freeSlot(): Int {
        for (slot in 1..config.maxClients) if (byPeer[slot] == null) return slot
        return NO_SLOT
    }

    private fun requireSecret(): ConnectionSecret =
        checkNotNull(secret) { "only a server mints connection tokens" }

    private fun unsignedShort(offset: Int): Int = BigEndian.getShort(received, offset).toInt() and 0xFFFF

    public companion object {

        /** Largest value the sixteen-bit protocol hash field can carry. */
        private const val PROTO_HASH_MAX: Int = (1 shl ProtocolDescriptor.PROTO_HASH_BITS) - 1

        /** A client keeps its one connection here, since slot ids belong to the server. */
        private const val SERVER_SLOT: Int = 0

        private const val NO_SLOT: Int = -1

        /** A client's peer id before the server has assigned one. */
        private const val UNASSIGNED_RAW: Int = -1

        /** Caps the handshake backoff at 32x the RTO floor before [UdpConfig.maxRtoTicks] bites. */
        private const val MAX_BACKOFF_DOUBLINGS: Int = 5

        private val EMPTY = ByteArray(0)

        /**
         * Binds a listening server.
         *
         * @param bindAddress the address to bind. Port zero takes an ephemeral one, readable
         *   afterwards from [localAddress].
         * @param secret the key this server mints and verifies connect tokens with. It never
         *   leaves the process.
         * @param protoHash this build's [dev.wildware.udea.net.wire.ProtocolDescriptor.protoHash].
         */
        public fun server(
            bindAddress: InetSocketAddress,
            clock: ManualClock,
            secret: ConnectionSecret,
            protoHash: Int,
            config: UdpConfig = UdpConfig(),
            listener: UdpConnectionListener = UdpConnectionListener.NONE,
        ): UdpTransport = open(bindAddress, config) { selector, socket ->
            UdpTransport(
                selector = selector,
                socket = socket,
                clock = clock,
                config = config,
                secret = secret,
                protoHash = protoHash,
                serverAddress = null,
                clientSalt = 0L,
                listener = listener,
            )
        }

        /**
         * Binds a client and begins connecting to [serverAddress].
         *
         * The handshake runs from [flush] and [poll]; nothing blocks and nothing sleeps, so a
         * caller polls [isConnected] from its own loop.
         *
         * @param clientSalt an opening nonce, supplied rather than generated. This package holds
         *   no random source by design (`NoWallClockInTransportTest`), and the value that
         *   actually has to be unguessable is the connection salt, which is this xored with a
         *   token the server derives from a key the client never sees.
         */
        public fun client(
            serverAddress: InetSocketAddress,
            clientSalt: Long,
            clock: ManualClock,
            protoHash: Int,
            config: UdpConfig = UdpConfig(),
            listener: UdpConnectionListener = UdpConnectionListener.NONE,
            bindAddress: InetSocketAddress? = null,
        ): UdpTransport = open(bindAddress, config) { selector, socket ->
            UdpTransport(
                selector = selector,
                socket = socket,
                clock = clock,
                config = config,
                secret = null,
                protoHash = protoHash,
                serverAddress = serverAddress,
                clientSalt = clientSalt,
                listener = listener,
            )
        }

        /**
         * Binds a socket and hands it to [build], closing both it and its selector if binding fails.
         *
         * Blocks the calling thread for the bind, which is a system call and not a wait on the network,
         * so the factories stay the plain functions they were before issue #209 rather than making
         * every caller a coroutine.
         */
        private fun open(
            bindAddress: InetSocketAddress?,
            config: UdpConfig,
            build: (SelectorManager, BoundDatagramSocket) -> UdpTransport,
        ): UdpTransport {
            val selector = SelectorManager()
            try {
                val socket = runBlocking {
                    aSocket(selector).udp().bind(bindAddress) {
                        receiveBufferSize = config.socketBufferBytes
                        sendBufferSize = config.socketBufferBytes
                    }
                }
                return build(selector, socket)
            } catch (error: Throwable) {
                selector.close()
                throw error
            }
        }
    }
}
