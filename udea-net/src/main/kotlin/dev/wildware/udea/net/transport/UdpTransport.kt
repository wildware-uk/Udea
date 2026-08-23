package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.net.wire.ProtocolDescriptor
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

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
 * A non-blocking `DatagramChannel` behind the same four-method SPI as [LoopbackTransport], so
 * the replication stack that was written and tested against an in-memory queue runs over the
 * internet without knowing it moved.
 *
 * ## It owns no threads and reads no wall clock
 *
 * Both properties are load-bearing rather than stylistic. No threads means the game loop
 * drives it — [flush] then [poll] then send, exactly the order [NetHarness] uses — so there is
 * no lock, no queue handoff and no race between "a packet arrived" and "the tick that would
 * consume it". No wall clock means every timeout, retry and keep-alive is a [Tick] read from
 * the injected [ManualClock], which is what lets `NoWallClockInTransportTest` scan this file
 * and what makes the whole package's failures reproducible. The clock is advanced by whatever
 * paces the game loop; this class never advances it.
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
    private val channel: DatagramChannel,
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
    private val sendView: ByteBuffer = ByteBuffer.wrap(sendBuffer)

    /**
     * Deliberately larger than the MTU.
     *
     * `DatagramChannel.receive` fills what it can and silently discards the rest, so a buffer
     * of exactly the MTU makes an oversized datagram indistinguishable from a legal one. With
     * the margin, anything over the MTU arrives with a length over the MTU and is refused as
     * what it is.
     */
    private val receiveBuffer = ByteArray(config.mtu + OVERSIZE_MARGIN_BYTES)
    private val receiveView: ByteBuffer = ByteBuffer.wrap(receiveBuffer)

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
    public val localAddress: InetSocketAddress = channel.localAddress as InetSocketAddress

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
            receiveView.clear()
            val source = try {
                channel.receive(receiveView)
            } catch (error: IOException) {
                // On Windows an ICMP port-unreachable from a peer that died surfaces here as a
                // failed receive on a perfectly healthy socket. Counting and carrying on is the
                // only correct response: the alternative is that killing one client kills the
                // server, which is one of the cases this transport exists to survive.
                counters.receiveErrors++
                taken++
                continue
            } ?: break
            taken++
            delivered += handle(source as InetSocketAddress, receiveView.position(), sink)
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
            sendView.clear()
            sendView.put(UdpLayout.TYPE, UdpPacketType.Disconnect.id.toByte())
            sendView.putLong(UdpLayout.DISCONNECT_SALT, connection.salt)
            repeat(config.disconnectSends) { transmit(connection.address, UdpLayout.DISCONNECT_BYTES) }
            connection.reassembler.clear()
            byPeer[index] = null
            listener.onDisconnected(connection.peer, DisconnectReason.LocalClosed)
        }
        byAddress.clear()
        channel.close()
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
        val type = UdpPacketType.of(receiveBuffer[UdpLayout.TYPE].toInt() and 0xFF)
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
        if (connection == null || connection.salt != receiveView.getLong(UdpLayout.PAYLOAD_SALT)) {
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
        connection.onAck(unsignedShort(UdpLayout.PAYLOAD_ACK), receiveView.getInt(UdpLayout.PAYLOAD_ACK_BITS), now)
        connection.stats.recordReceived(length)
        if (receiveBuffer[UdpLayout.PAYLOAD_FRAGMENT_FLAG].toInt() != UdpLayout.FRAGMENT_FLAG_SET) {
            val body = length - UdpLayout.PAYLOAD_HEADER_BYTES
            if (body == 0) return 0
            sink.receive(connection.peer, receiveBuffer, UdpLayout.PAYLOAD_HEADER_BYTES, body)
            return 1
        }
        if (length < UdpLayout.FRAGMENT_HEADER_BYTES) {
            counters.malformed++
            return 0
        }
        val assembly = connection.reassembler.accept(
            messageId = unsignedShort(UdpLayout.FRAGMENT_MESSAGE_ID),
            index = receiveBuffer[UdpLayout.FRAGMENT_INDEX].toInt() and 0xFF,
            count = receiveBuffer[UdpLayout.FRAGMENT_COUNT].toInt() and 0xFF,
            source = receiveBuffer,
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
        if (connection.salt != receiveView.getLong(UdpLayout.DISCONNECT_SALT)) {
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
        val salt = receiveView.getLong(UdpLayout.REQUEST_CLIENT_SALT)
        val expiry = clock.tick + config.tokenLifetimeTicks
        val token = requireSecret().token(source, salt, expiry)
        sendView.clear()
        sendView.put(UdpLayout.TYPE, UdpPacketType.ConnectionChallenge.id.toByte())
        sendView.putLong(UdpLayout.CHALLENGE_CLIENT_SALT, salt)
        sendView.putLong(UdpLayout.CHALLENGE_TOKEN, token)
        sendView.putLong(UdpLayout.CHALLENGE_EXPIRY, expiry.value)
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
        val salt = receiveView.getLong(UdpLayout.RESPONSE_CLIENT_SALT)
        val token = receiveView.getLong(UdpLayout.RESPONSE_TOKEN)
        val expiry = Tick(receiveView.getLong(UdpLayout.RESPONSE_EXPIRY))
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
        sendView.clear()
        sendView.put(UdpLayout.TYPE, UdpPacketType.ConnectionAccepted.id.toByte())
        sendView.putLong(UdpLayout.ACCEPTED_SALT, connection.salt)
        sendView.putShort(UdpLayout.ACCEPTED_PEER, connection.peer.raw.toShort())
        transmitUnverified(connection.address, UdpLayout.ACCEPTED_BYTES, receivedBytes)
    }

    private fun deny(source: InetSocketAddress, reason: DisconnectReason, receivedBytes: Int) {
        sendView.clear()
        sendView.put(UdpLayout.TYPE, UdpPacketType.ConnectionDenied.id.toByte())
        sendView.put(UdpLayout.DENIED_REASON, reason.id.toByte())
        if (transmitUnverified(source, UdpLayout.DENIED_BYTES, receivedBytes)) counters.handshakesDenied++
    }

    // --- client side of the handshake -------------------------------------------------------

    private fun onConnectionChallenge(source: InetSocketAddress, length: Int): Int {
        if (isServer || source != serverAddress || handshakeState != HandshakeState.Requesting) {
            counters.malformed++
            return 0
        }
        if (length < UdpLayout.CHALLENGE_BYTES ||
            receiveView.getLong(UdpLayout.CHALLENGE_CLIENT_SALT) != clientSalt
        ) {
            counters.malformed++
            return 0
        }
        challengeToken = receiveView.getLong(UdpLayout.CHALLENGE_TOKEN)
        challengeExpiry = Tick(receiveView.getLong(UdpLayout.CHALLENGE_EXPIRY))
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
        val salt = receiveView.getLong(UdpLayout.ACCEPTED_SALT)
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
        val reason = DisconnectReason.of(receiveBuffer[UdpLayout.DENIED_REASON].toInt() and 0xFF)
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
        sendView.clear()
        val bytes = when (handshakeState) {
            HandshakeState.Requesting -> {
                sendView.put(UdpLayout.TYPE, UdpPacketType.ConnectionRequest.id.toByte())
                sendView.putShort(UdpLayout.REQUEST_PROTO_HASH, protoHash.toShort())
                sendView.putLong(UdpLayout.REQUEST_CLIENT_SALT, clientSalt)
                pad(UdpLayout.REQUEST_BODY_BYTES)
            }
            HandshakeState.Responding -> {
                sendView.put(UdpLayout.TYPE, UdpPacketType.ConnectionResponse.id.toByte())
                sendView.putShort(UdpLayout.RESPONSE_PROTO_HASH, protoHash.toShort())
                sendView.putLong(UdpLayout.RESPONSE_CLIENT_SALT, clientSalt)
                sendView.putLong(UdpLayout.RESPONSE_TOKEN, challengeToken)
                sendView.putLong(UdpLayout.RESPONSE_EXPIRY, challengeExpiry.value)
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
        sendView.clear()
        writePayloadHeader(connection, now, fragmented = false)
        System.arraycopy(bytes, offset, sendBuffer, UdpLayout.PAYLOAD_HEADER_BYTES, length)
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
        sendView.clear()
        writePayloadHeader(connection, now, fragmented = true)
        sendView.putShort(UdpLayout.FRAGMENT_MESSAGE_ID, messageId.toShort())
        sendView.put(UdpLayout.FRAGMENT_INDEX, index.toByte())
        sendView.put(UdpLayout.FRAGMENT_COUNT, count.toByte())
        System.arraycopy(bytes, offset, sendBuffer, UdpLayout.FRAGMENT_HEADER_BYTES, length)
        val total = UdpLayout.FRAGMENT_HEADER_BYTES + length
        if (transmit(connection.address, total)) connection.stats.recordSent(total)
    }

    private fun writePayloadHeader(connection: UdpConnection, now: Tick, fragmented: Boolean) {
        sendView.put(UdpLayout.TYPE, UdpPacketType.Payload.id.toByte())
        sendView.putLong(UdpLayout.PAYLOAD_SALT, connection.salt)
        sendView.putShort(UdpLayout.PAYLOAD_SEQ, connection.beginSend(now).toShort())
        val ack = if (connection.remoteSeq == UdpConnection.NO_SEQ) 0 else connection.remoteSeq
        sendView.putShort(UdpLayout.PAYLOAD_ACK, (ack and PacketHeader.SEQ_MASK).toShort())
        sendView.putInt(UdpLayout.PAYLOAD_ACK_BITS, connection.remoteAckBits)
        sendView.put(
            UdpLayout.PAYLOAD_FRAGMENT_FLAG,
            (if (fragmented) UdpLayout.FRAGMENT_FLAG_SET else 0).toByte(),
        )
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
        sendView.position(0)
        sendView.limit(length)
        return try {
            channel.send(sendView, target)
            true
        } catch (error: IOException) {
            // Same Windows ICMP story as the receive path, plus a transient ENOBUFS on a
            // saturated link. A send that fails is a dropped datagram, which this transport is
            // allowed to do at any time; it is never a reason to stop serving other peers.
            counters.receiveErrors++
            false
        } finally {
            sendView.clear()
        }
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

    private fun unsignedShort(offset: Int): Int = receiveView.getShort(offset).toInt() and 0xFFFF

    public companion object {

        /**
         * How far past the MTU the receive buffer reaches.
         *
         * Only has to be one byte to detect an oversized datagram; 256 gives room to log what
         * arrived without a second buffer, and is negligible next to the socket buffer.
         */
        public const val OVERSIZE_MARGIN_BYTES: Int = 256

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
        ): UdpTransport = UdpTransport(
            channel = open(bindAddress, config),
            clock = clock,
            config = config,
            secret = secret,
            protoHash = protoHash,
            serverAddress = null,
            clientSalt = 0L,
            listener = listener,
        )

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
            bindAddress: InetSocketAddress = InetSocketAddress(0),
        ): UdpTransport = UdpTransport(
            channel = open(bindAddress, config),
            clock = clock,
            config = config,
            secret = null,
            protoHash = protoHash,
            serverAddress = serverAddress,
            clientSalt = clientSalt,
            listener = listener,
        )

        private fun open(bindAddress: InetSocketAddress, config: UdpConfig): DatagramChannel {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)
            channel.setOption(StandardSocketOptions.SO_RCVBUF, config.socketBufferBytes)
            channel.setOption(StandardSocketOptions.SO_SNDBUF, config.socketBufferBytes)
            channel.bind(bindAddress)
            return channel
        }
    }
}
