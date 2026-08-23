package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick

/**
 * One datagram in flight, owned by a pool.
 *
 * [buffer] is MTU-sized and reused: a datagram is taken from the pool on send and returned to
 * it once delivered or dropped, so a steady-state tick allocates nothing. That is the direct
 * answer to `PacketUtil.kt:167`, where every `EntityUpdate` was obtained from a pool and never
 * freed, and to `packets.kt:66`, where every packet allocated a fixed 2048-byte array.
 */
internal class Datagram(mtu: Int) {
    val buffer: ByteArray = ByteArray(mtu)
    var length: Int = 0
    var from: PeerId = PeerId.SERVER
    var to: PeerId = PeerId.SERVER

    /** Tick at which this becomes visible to the receiver. Set by [SimulatedTransport]. */
    var deliverAt: Tick = Tick.ZERO

    /** Monotonic send order, so equal [deliverAt] values keep a defined, reproducible order. */
    var sendOrder: Long = 0L

    fun fill(from: PeerId, to: PeerId, source: ByteArray, offset: Int, length: Int) {
        this.from = from
        this.to = to
        this.length = length
        System.arraycopy(source, offset, buffer, 0, length)
    }
}

/**
 * A free list of [Datagram]s, one per network.
 *
 * Deliberately never shrinking: the high-water mark is the most datagrams simultaneously in
 * flight, which for a fixed session is a small constant, so the pool warms up within a handful
 * of ticks and then never allocates again.
 */
internal class DatagramPool(private val mtu: Int) {

    private val free = ArrayList<Datagram>()

    /** How many datagrams have ever been created. A test asserts this stops growing. */
    var created: Int = 0
        private set

    fun take(): Datagram {
        val pooled = free.removeLastOrNull()
        if (pooled != null) return pooled
        created++
        return Datagram(mtu)
    }

    fun give(datagram: Datagram) {
        datagram.length = 0
        free.add(datagram)
    }
}

/**
 * A caller tried to send more than one datagram can hold.
 *
 * Typed and loud, because the alternative is what `packets.kt:117` did: write a fixed
 * 2048-byte buffer and hand the whole thing to a transport with a 1500-byte MTU, where the
 * truncation happens somewhere else and presents as corruption.
 */
public class DatagramTooLargeException(
    public val length: Int,
    public val mtu: Int,
) : IllegalArgumentException("datagram of $length byte(s) exceeds the $mtu byte MTU")

/**
 * A whole session of endpoints wired to each other by in-memory queues.
 *
 * This is Trello #8 made structural: the server and every client are ordinary [Transport]s in
 * one JVM, on one thread, with no sockets and no threads, so the same code path that serves a
 * remote client serves the listen server's own local client. The listen server is therefore
 * not a special case exercised only by playing the game — it is the default case every test
 * runs.
 *
 * Delivery is immediate: [LoopbackTransport.send] appends straight to the destination inbox
 * and the next [LoopbackTransport.poll] sees it. Latency, loss and reordering are not this
 * class's business; they belong to [SimulatedTransport], layered on top, so the zero-latency
 * case stays honestly zero-latency.
 */
public class LoopbackNetwork(

    /** The clock every transport in this network reads. */
    public val clock: ManualClock,

    /** Largest datagram this network will carry, in bytes. */
    public val mtu: Int = DEFAULT_MTU,

    /** Where every send, drop and delivery is recorded. */
    public val log: PacketLog = PacketLog(),
) {

    private val pool = DatagramPool(mtu)
    private val inboxes = HashMap<Int, ArrayDeque<Datagram>>()
    private val transports = HashMap<Int, LoopbackTransport>()

    /** How many [Datagram] buffers have ever been allocated. Flat in steady state. */
    public val allocatedDatagrams: Int get() = pool.created

    /**
     * The transport for [peer], creating it on first request.
     *
     * Idempotent: two calls with the same [peer] return the same instance, so a caller may ask
     * for the server transport from anywhere without threading it through.
     */
    public fun transportFor(peer: PeerId): LoopbackTransport =
        transports.getOrPut(peer.raw) { LoopbackTransport(peer, this) }

    internal fun enqueue(from: PeerId, to: PeerId, bytes: ByteArray, offset: Int, length: Int) {
        if (length > mtu) throw DatagramTooLargeException(length, mtu)
        val datagram = pool.take()
        datagram.fill(from, to, bytes, offset, length)
        inbox(to).addLast(datagram)
    }

    internal fun drain(peer: PeerId, sink: DatagramSink, statsOf: (PeerId) -> TransportStats): Int {
        val queue = inbox(peer)
        var delivered = 0
        // Snapshot the queue length first: a sink that sends a reply must not have that reply
        // delivered inside the same drain, or delivery order stops being a function of ticks.
        var remaining = queue.size
        while (remaining > 0) {
            val datagram = queue.removeFirst()
            remaining--
            log.record(
                clock.tick,
                PacketEventKind.Delivered,
                datagram.from,
                datagram.to,
                datagram.buffer,
                0,
                datagram.length,
            )
            statsOf(datagram.from).recordReceived(datagram.length)
            sink.receive(datagram.from, datagram.buffer, 0, datagram.length)
            pool.give(datagram)
            delivered++
        }
        return delivered
    }

    internal fun discard(peer: PeerId) {
        val queue = inboxes[peer.raw] ?: return
        while (queue.isNotEmpty()) pool.give(queue.removeFirst())
    }

    private fun inbox(peer: PeerId): ArrayDeque<Datagram> = inboxes.getOrPut(peer.raw) { ArrayDeque() }

    public companion object {

        /**
         * 1200 bytes, not 1500.
         *
         * 1500 is the Ethernet MTU, not the usable payload: IPv6 plus UDP headers take 48 of it
         * before a byte of ours, and any tunnel takes more. 1200 is the figure QUIC settled on
         * for the same reason, and the target spec section 5 names for a snapshot datagram.
         */
        public const val DEFAULT_MTU: Int = 1200
    }
}

/**
 * One endpoint's view of a [LoopbackNetwork].
 *
 * Holds no buffers of its own — the network owns the pool — so constructing one per client is
 * cheap and closing one only drops that endpoint's undelivered traffic.
 */
public class LoopbackTransport internal constructor(
    override val localPeer: PeerId,
    private val network: LoopbackNetwork,
) : Transport {

    private val statsByPeer = HashMap<Int, TransportStats>()
    private var closed = false

    override fun send(peer: PeerId, bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "$localPeer transport is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "slice [$offset, ${offset + length}) does not fit a ${bytes.size} byte buffer"
        }
        network.enqueue(localPeer, peer, bytes, offset, length)
        network.log.record(network.clock.tick, PacketEventKind.Sent, localPeer, peer, bytes, offset, length)
        stats(peer).recordSent(length)
    }

    override fun poll(sink: DatagramSink): Int {
        if (closed) return 0
        return network.drain(localPeer, sink, ::stats)
    }

    override fun stats(peer: PeerId): TransportStats =
        statsByPeer.getOrPut(peer.raw) { TransportStats(peer) }

    override fun close() {
        if (closed) return
        closed = true
        network.discard(localPeer)
    }
}
