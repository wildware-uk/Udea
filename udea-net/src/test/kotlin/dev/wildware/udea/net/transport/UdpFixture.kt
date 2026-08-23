package dev.wildware.udea.net.transport

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * A key the tests share.
 *
 * Fixed bytes rather than generated ones: a token minted under this key is the same token on
 * every run, so a failing handshake assertion is reproducible rather than a thing that happened
 * once. Nothing about the security of the scheme depends on the key being secret *in a test*.
 */
internal fun testSecret(): ConnectionSecret = ConnectionSecret(ByteArray(ConnectionSecret.MIN_KEY_BYTES) { it.toByte() })

/** The 16-bit protocol hash the fixtures agree on. */
internal const val TEST_PROTO_HASH: Int = 0x2A5F

internal fun loopback(port: Int): InetSocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), port)

/** Everything one endpoint received, copied out of the transport's buffer at delivery. */
internal class RecordingSink : DatagramSink {

    val messages = ArrayList<ByteArray>()
    val senders = ArrayList<PeerId>()

    override fun receive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
        senders += from
        messages += buffer.copyOfRange(offset, offset + length)
    }

    fun clear() {
        messages.clear()
        senders.clear()
    }
}

/** Every connection event, so a test can assert on the reason a link ended. */
internal class RecordingListener : UdpConnectionListener {

    val connected = ArrayList<PeerId>()
    val disconnected = ArrayList<Pair<PeerId, DisconnectReason>>()

    override fun onConnected(peer: PeerId) {
        connected += peer
    }

    override fun onDisconnected(peer: PeerId, reason: DisconnectReason) {
        disconnected += peer to reason
    }
}

/**
 * A server and a client on real loopback sockets, driven by one manual clock.
 *
 * There is no `Thread.sleep` and no wait loop with a wall-clock deadline anywhere in here. A
 * step advances the clock, gives both transports their tick hook, and drains both sockets;
 * loopback delivery is synchronous inside the kernel's send call, so a datagram sent during one
 * step is readable in the next. [pumpUntil] therefore terminates in a bounded number of steps
 * or fails, rather than spinning against a timeout — which is the difference between a network
 * test and a flaky one.
 */
internal class UdpPair(
    serverConfig: UdpConfig = UdpConfig(),
    clientConfig: UdpConfig = serverConfig,
    clientSalt: Long = 0x0123_4567_89AB_CDEFL,
    protoHash: Int = TEST_PROTO_HASH,
    clientProtoHash: Int = protoHash,
) : AutoCloseable {

    val clock: ManualClock = ManualClock()
    val serverEvents: RecordingListener = RecordingListener()
    val clientEvents: RecordingListener = RecordingListener()
    val serverSink: RecordingSink = RecordingSink()
    val clientSink: RecordingSink = RecordingSink()

    val server: UdpTransport = UdpTransport.server(
        bindAddress = loopback(0),
        clock = clock,
        secret = testSecret(),
        protoHash = protoHash,
        config = serverConfig,
        listener = serverEvents,
    )

    val client: UdpTransport = UdpTransport.client(
        serverAddress = loopback(server.localAddress.port),
        clientSalt = clientSalt,
        clock = clock,
        protoHash = clientProtoHash,
        config = clientConfig,
        listener = clientEvents,
    )

    /** One tick of the loop the game runs: advance, tick hook, drain. */
    fun step() {
        clock.advance()
        server.flush()
        client.flush()
        server.poll(serverSink)
        client.poll(clientSink)
    }

    /** Steps until [condition] holds, up to [limit] steps. Returns whether it held. */
    fun pumpUntil(limit: Int = DEFAULT_LIMIT, condition: () -> Boolean): Boolean {
        repeat(limit) {
            step()
            if (condition()) return true
        }
        return condition()
    }

    /** Steps [count] times unconditionally. */
    fun step(count: Int) {
        repeat(count) { step() }
    }

    /** Runs the handshake to completion, failing the fixture if it does not converge. */
    fun connect(): PeerId {
        check(pumpUntil { client.isConnected && server.isConnected }) {
            "the handshake did not complete: client=${client.failure} " +
                "server=${server.counters} client=${client.counters}"
        }
        return client.localPeer
    }

    override fun close() {
        client.close()
        server.close()
    }

    companion object {

        /**
         * 240 steps.
         *
         * Well under the 600-tick default timeout, so a fixture that fails to converge fails as
         * "the handshake did not complete" rather than as a timeout that hides the real cause.
         */
        const val DEFAULT_LIMIT: Int = 240
    }
}

/**
 * A socket that speaks the wire format by hand.
 *
 * The hostile-case tests need to send things no [UdpTransport] would ever send — a truncated
 * datagram, an unpadded connect request, a payload replayed verbatim — and they need to send
 * some of them from an address the server already trusts. A second `UdpTransport` cannot do any
 * of that, so the attacker is a raw channel and the test writes the bytes.
 */
internal class RawPeer(private val server: InetSocketAddress, private val mtu: Int = UdpConfig().mtu) : AutoCloseable {

    private val channel: DatagramChannel = DatagramChannel.open().apply {
        configureBlocking(false)
        bind(loopback(0))
    }

    private val inbound = ByteBuffer.allocate(mtu + UdpTransport.OVERSIZE_MARGIN_BYTES)

    /** This peer's own address, which is what the server keys its connection on. */
    val address: InetSocketAddress get() = channel.localAddress as InetSocketAddress

    /** The connection salt, once [handshake] has completed. */
    var salt: Long = 0L
        private set

    /** The peer id the server handed out, once [handshake] has completed. */
    var assignedPeer: Int = 0
        private set

    private var sequence: Int = 0

    fun send(bytes: ByteArray) {
        channel.send(ByteBuffer.wrap(bytes), server)
    }

    /** The next datagram waiting, or null. */
    fun receive(): ByteArray? {
        inbound.clear()
        channel.receive(inbound) ?: return null
        return inbound.array().copyOfRange(0, inbound.position())
    }

    /** Drains and counts everything waiting. */
    fun drain(): List<ByteArray> {
        val all = ArrayList<ByteArray>()
        while (true) all += receive() ?: return all
    }

    /** A connect request padded to the MTU, as the real client sends it. */
    fun connectionRequest(clientSalt: Long, protoHash: Int = TEST_PROTO_HASH, length: Int = mtu): ByteArray {
        val bytes = ByteArray(length)
        val view = ByteBuffer.wrap(bytes)
        view.put(UdpLayout.TYPE, UdpPacketType.ConnectionRequest.id.toByte())
        view.putShort(UdpLayout.REQUEST_PROTO_HASH, protoHash.toShort())
        view.putLong(UdpLayout.REQUEST_CLIENT_SALT, clientSalt)
        return bytes
    }

    private fun connectionResponse(clientSalt: Long, token: Long, expiry: Long): ByteArray {
        val bytes = ByteArray(mtu)
        val view = ByteBuffer.wrap(bytes)
        view.put(UdpLayout.TYPE, UdpPacketType.ConnectionResponse.id.toByte())
        view.putShort(UdpLayout.RESPONSE_PROTO_HASH, TEST_PROTO_HASH.toShort())
        view.putLong(UdpLayout.RESPONSE_CLIENT_SALT, clientSalt)
        view.putLong(UdpLayout.RESPONSE_TOKEN, token)
        view.putLong(UdpLayout.RESPONSE_EXPIRY, expiry)
        return bytes
    }

    /** A payload datagram this peer would legitimately send, given its salt. */
    fun payload(body: ByteArray, seq: Int = sequence++): ByteArray {
        val bytes = ByteArray(UdpLayout.PAYLOAD_HEADER_BYTES + body.size)
        val view = ByteBuffer.wrap(bytes)
        view.put(UdpLayout.TYPE, UdpPacketType.Payload.id.toByte())
        view.putLong(UdpLayout.PAYLOAD_SALT, salt)
        view.putShort(UdpLayout.PAYLOAD_SEQ, seq.toShort())
        view.putShort(UdpLayout.PAYLOAD_ACK, 0)
        view.putInt(UdpLayout.PAYLOAD_ACK_BITS, 0)
        view.put(UdpLayout.PAYLOAD_FRAGMENT_FLAG, 0)
        System.arraycopy(body, 0, bytes, UdpLayout.PAYLOAD_HEADER_BYTES, body.size)
        return bytes
    }

    /**
     * Completes a real three-way handshake, driving [pump] between the legs.
     *
     * @return true when the server accepted. False means the server refused somewhere, and the
     *   caller asserts on which counter moved.
     */
    fun handshake(clientSalt: Long, pump: () -> Unit): Boolean {
        send(connectionRequest(clientSalt))
        val challenge = await(pump) { it[UdpLayout.TYPE].toInt() == UdpPacketType.ConnectionChallenge.id }
            ?: return false
        val view = ByteBuffer.wrap(challenge)
        val token = view.getLong(UdpLayout.CHALLENGE_TOKEN)
        val expiry = view.getLong(UdpLayout.CHALLENGE_EXPIRY)
        send(connectionResponse(clientSalt, token, expiry))
        val accepted = await(pump) { it[UdpLayout.TYPE].toInt() == UdpPacketType.ConnectionAccepted.id }
            ?: return false
        val acceptedView = ByteBuffer.wrap(accepted)
        salt = acceptedView.getLong(UdpLayout.ACCEPTED_SALT)
        assignedPeer = acceptedView.getShort(UdpLayout.ACCEPTED_PEER).toInt() and 0xFFFF
        return true
    }

    private fun await(pump: () -> Unit, matches: (ByteArray) -> Boolean): ByteArray? {
        repeat(UdpPair.DEFAULT_LIMIT) {
            pump()
            for (datagram in drain()) if (matches(datagram)) return datagram
        }
        return null
    }

    override fun close() {
        channel.close()
    }
}

/**
 * A server with no client, for the tests whose peer is a [RawPeer].
 *
 * Separate from [UdpPair] because a real client sitting on the same server would be generating
 * its own handshake and keep-alive traffic, and a counter assertion about what an attacker
 * caused has to be about what the attacker caused.
 */
internal class UdpServerOnly(
    config: UdpConfig = UdpConfig(),
    protoHash: Int = TEST_PROTO_HASH,
) : AutoCloseable {

    val clock: ManualClock = ManualClock()
    val events: RecordingListener = RecordingListener()
    val sink: RecordingSink = RecordingSink()

    val server: UdpTransport = UdpTransport.server(
        bindAddress = loopback(0),
        clock = clock,
        secret = testSecret(),
        protoHash = protoHash,
        config = config,
        listener = events,
    )

    /** Where a [RawPeer] should aim. */
    val address: InetSocketAddress get() = loopback(server.localAddress.port)

    fun step() {
        clock.advance()
        server.flush()
        server.poll(sink)
    }

    fun step(count: Int) {
        repeat(count) { step() }
    }

    override fun close() {
        server.close()
    }
}
