package dev.wildware.udea.net.transport

/**
 * Which endpoint a datagram came from or is going to.
 *
 * A dense small integer rather than a socket address, because the SPI must be satisfiable by
 * an in-memory queue as easily as by UDP: `LoopbackTransport` has no addresses at all, and a
 * test that wants to name "client 2" should not have to invent a fake `InetSocketAddress`.
 *
 * [SERVER] is fixed at zero and clients start at one, so an `IntArray` indexed by peer id is
 * the natural per-peer store everywhere above this layer.
 */
@JvmInline
public value class PeerId(public val raw: Int) : Comparable<PeerId> {

    override fun compareTo(other: PeerId): Int = raw.compareTo(other.raw)

    override fun toString(): String = if (raw == SERVER_RAW) "server" else "client$raw"

    public companion object {

        private const val SERVER_RAW: Int = 0

        /** The host endpoint. Always zero. */
        public val SERVER: PeerId = PeerId(SERVER_RAW)

        /** The nth client, one-based, so that zero stays [SERVER]. */
        public fun client(oneBasedIndex: Int): PeerId {
            require(oneBasedIndex >= 1) { "client index is one-based, was $oneBasedIndex" }
            return PeerId(oneBasedIndex)
        }
    }
}

/**
 * Where [Transport.poll] hands each received datagram.
 *
 * A callback taking a slice rather than a `List<ByteArray>` return, and that is the whole
 * point: the buffer handed to [receive] belongs to the transport and is recycled the instant
 * [receive] returns. A caller that needs the bytes past the call must copy them. In exchange,
 * draining a tick's traffic allocates nothing — the property the old
 * `EntityUpdatePool`/`sendToAllUDP` path in `NetworkServerSystem` failed to hold, where every
 * entity per client per tick minted an `EntityUpdate` and never returned it.
 *
 * A `fun interface` so a lambda sink is a singleton, not a per-poll allocation.
 */
public fun interface DatagramSink {

    /**
     * @param from the peer that sent it.
     * @param buffer the transport's buffer. Valid only for the duration of this call.
     * @param offset first payload byte in [buffer].
     * @param length payload length in bytes.
     */
    public fun receive(from: PeerId, buffer: ByteArray, offset: Int, length: Int)
}

/**
 * Moving opaque bytes between endpoints, and nothing else.
 *
 * This layer knows nothing about entities, components, acks or reliability: it hands over
 * datagrams and counts them. Everything above it — framing, snapshots, baselines — is written
 * once against this interface and therefore runs identically over an in-memory queue and over
 * a socket, which is what makes `LoopbackTransport` a real test substrate rather than a mock.
 *
 * ## Non-negotiable properties
 *
 * - **No blocking.** Nothing here waits. [poll] returns what has arrived and returns
 *   immediately if that is nothing.
 * - **No threads owned by the interface.** An implementation may be fed by one, but the SPI
 *   never starts one, so a test drives the whole stack on the calling thread with a manual
 *   clock and no `Thread.sleep` anywhere. That is a hard requirement of the harness, not a
 *   preference: a sleep is how a network test becomes flaky.
 * - **Unreliable and unordered.** A datagram may be dropped, duplicated or reordered.
 *   [SimulatedTransport] makes that a first-class, seeded, reproducible property rather than
 *   something discovered in production.
 *
 * Contrast with KryoNet, which the old tree used: two sockets (TCP and UDP) with independent
 * ordering and no shared ack or RTT view, and no way to drive either from a manual clock.
 */
public interface Transport {

    /** This endpoint's own id. */
    public val localPeer: PeerId

    /**
     * Queues `bytes[offset, offset + length)` for delivery to [peer].
     *
     * The caller's array is read before this returns and never retained, so the caller may
     * reuse one MTU-sized scratch buffer for every send of every tick.
     */
    public fun send(peer: PeerId, bytes: ByteArray, offset: Int, length: Int)

    /**
     * Hands every datagram that has arrived to [sink] and returns how many there were.
     *
     * @return the number of datagrams delivered, so a caller can assert on quiet ticks.
     */
    public fun poll(sink: DatagramSink): Int

    /**
     * Counters for traffic with [peer].
     *
     * The returned object is live and updates in place: copying a counter object per query would
     * be exactly the per-tick allocation the rest of this module exists to avoid, and a caller
     * that needs a reading stable across ticks reads the fields it cares about.
     */
    public fun stats(peer: PeerId): TransportStats

    /** Releases buffers and drops undelivered traffic. Idempotent. */
    public fun close()
}

/**
 * Per-peer traffic counters, live rather than snapshotted.
 *
 * Mutated by the transport and read by tests and by `net.bandwidth`. Returned live because
 * copying a counter object per query is exactly the sort of per-tick allocation the rest of
 * this module exists to avoid; callers that want a stable reading take one with [copy].
 */
public class TransportStats internal constructor(

    /** The remote endpoint these counters describe. */
    public val peer: PeerId,
) {

    /** Datagrams handed to [Transport.send] and not dropped before leaving. */
    public var packetsSent: Long = 0L
        internal set

    /** Payload bytes in [packetsSent]. Excludes any simulated framing overhead. */
    public var bytesSent: Long = 0L
        internal set

    /** Datagrams delivered to a [DatagramSink]. */
    public var packetsReceived: Long = 0L
        internal set

    /** Payload bytes in [packetsReceived]. */
    public var bytesReceived: Long = 0L
        internal set

    /** Datagrams the simulation discarded. Never non-zero on a plain [LoopbackTransport]. */
    public var packetsDropped: Long = 0L
        internal set

    internal fun recordSent(length: Int) {
        packetsSent++
        bytesSent += length.toLong()
    }

    internal fun recordReceived(length: Int) {
        packetsReceived++
        bytesReceived += length.toLong()
    }

    override fun toString(): String =
        "TransportStats($peer, sent=$packetsSent/${bytesSent}B, " +
            "recv=$packetsReceived/${bytesReceived}B, dropped=$packetsDropped)"
}
