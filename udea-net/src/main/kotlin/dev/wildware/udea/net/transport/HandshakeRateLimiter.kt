package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import java.net.InetSocketAddress

/**
 * How much handshake work one source address, and the server as a whole, may cause per tick.
 *
 * ## The two limits, and why one of them is not enough
 *
 * A **global** budget is the only thing that actually bounds the server's cost: an attacker
 * with a botnet has as many source addresses as it likes, so a per-address bucket alone bounds
 * nothing. But a global budget alone is worse than useless during an attack, because the flood
 * spends it and a real player's connect attempt lands on an empty budget forever. So both:
 * the global budget caps the work, and the per-address bucket stops any one address eating it.
 *
 * ## Why the bucket is keyed on the address without the port
 *
 * A limiter keyed on `InetSocketAddress` is defeated by incrementing the source port, which
 * costs an attacker nothing and hands it a fresh, full bucket per datagram. Keying on the
 * address alone means all of one host's ports share one bucket.
 *
 * ## Why the table is fixed and lossy
 *
 * It is a rate limiter for a flood; if it allocated a row per source address it would be the
 * thing the flood exhausts. So the table is a fixed number of slots and a colliding address
 * evicts the least recently seen of the slots it probes. The failure mode of that eviction is
 * that two colliding addresses get each other's fresh bucket — which lets through at most a
 * few extra attempts, still under the global budget that is doing the real bounding.
 *
 * Rates are per [Tick], like every other duration in this package, so the limiter means the
 * same thing on a machine that runs the loop fast and one that runs it slow.
 */
internal class HandshakeRateLimiter(

    /** Slots in the table. Rounded up to a power of two. */
    capacity: Int = DEFAULT_CAPACITY,

    /** Attempts one address may make back to back after being quiet. */
    private val burst: Float = DEFAULT_BURST,

    /** Attempts one address earns back per tick. */
    private val refillPerTick: Float = DEFAULT_REFILL_PER_TICK,

    /** Attempts the whole server will service in one tick, whatever their source. */
    private val globalPerTick: Int = DEFAULT_GLOBAL_PER_TICK,
) {

    private val mask: Int = tableSize(capacity) - 1
    private val keys = IntArray(mask + 1)
    private val occupied = BooleanArray(mask + 1)
    private val tokens = FloatArray(mask + 1)
    private val seenAt = LongArray(mask + 1)

    private var globalTick: Tick = Tick(Long.MIN_VALUE)
    private var globalRemaining: Int = 0

    /** Attempts refused because the source address had spent its bucket. */
    var addressLimited: Long = 0L
        private set

    /** Attempts refused because the whole server had spent this tick's budget. */
    var globalLimited: Long = 0L
        private set

    /**
     * Whether a handshake datagram from [address] may be serviced at [now].
     *
     * Consumes budget when it returns true and consumes nothing when it returns false, so a
     * refused attempt cannot itself push a well-behaved peer over the line.
     */
    fun allow(address: InetSocketAddress, now: Tick): Boolean {
        if (globalTick != now) {
            globalTick = now
            globalRemaining = globalPerTick
        }
        if (globalRemaining <= 0) {
            globalLimited++
            return false
        }
        val slot = slotFor(hostKey(address), now)
        if (tokens[slot] < 1f) {
            addressLimited++
            return false
        }
        tokens[slot] -= 1f
        globalRemaining--
        return true
    }

    /**
     * The slot for [key], refilled to [now], evicting the stalest probed slot if it is new.
     *
     * Linear probing over a short fixed span rather than to the end of the table: an unbounded
     * probe under a flood is a scan of the whole table per datagram, which is the linear-scan
     * smell §1 names, at the worst possible moment.
     */
    private fun slotFor(key: Int, now: Tick): Int {
        val start = scramble(key) and mask
        var stalest = start
        for (probe in 0 until PROBE_SPAN) {
            val slot = (start + probe) and mask
            if (!occupied[slot]) return claim(slot, key, now)
            if (keys[slot] == key) return refill(slot, now)
            if (seenAt[slot] < seenAt[stalest]) stalest = slot
        }
        return claim(stalest, key, now)
    }

    private fun claim(slot: Int, key: Int, now: Tick): Int {
        occupied[slot] = true
        keys[slot] = key
        tokens[slot] = burst
        seenAt[slot] = now.value
        return slot
    }

    private fun refill(slot: Int, now: Tick): Int {
        val elapsed = now.value - seenAt[slot]
        if (elapsed > 0L) {
            tokens[slot] = minOf(burst, tokens[slot] + elapsed.toFloat() * refillPerTick)
            seenAt[slot] = now.value
        }
        return slot
    }

    /** The address without its port, so a new source port does not buy a new bucket. */
    private fun hostKey(address: InetSocketAddress): Int =
        if (address.isUnresolved) address.hostString.hashCode() else address.address.hashCode()

    /**
     * Spreads a key across the table.
     *
     * `InetAddress.hashCode` for IPv4 is the four address bytes as an int, so consecutive
     * addresses in a subnet land in consecutive slots and a `/24` sweep would walk one probe
     * span. The finalizer breaks that up.
     */
    private fun scramble(key: Int): Int {
        var hash = key
        hash = hash xor (hash ushr 16)
        hash *= MURMUR_MIX_A
        hash = hash xor (hash ushr 13)
        hash *= MURMUR_MIX_B
        return (hash xor (hash ushr 16)) and Int.MAX_VALUE
    }

    companion object {

        /**
         * 1024 slots: roughly 16KB of arrays, enough that a few hundred genuine clients never
         * collide, and small enough that the table itself is never the memory an attacker is
         * trying to consume.
         */
        const val DEFAULT_CAPACITY: Int = 1024

        /** Four attempts back to back covers a client retransmitting through a loss burst. */
        const val DEFAULT_BURST: Float = 4f

        /** One attempt per twenty ticks: three a second at 60Hz, far above a real client's need. */
        const val DEFAULT_REFILL_PER_TICK: Float = 0.05f

        /**
         * 32 handshake datagrams per tick — about 1900 a second at 60Hz.
         *
         * Each one costs an HMAC over forty bytes, so this bounds the server's handshake cost
         * at well under a millisecond per tick even while it is being flooded.
         */
        const val DEFAULT_GLOBAL_PER_TICK: Int = 32

        /** How far a collision probes before evicting. */
        private const val PROBE_SPAN: Int = 4

        private const val MURMUR_MIX_A: Int = -2048144789
        private const val MURMUR_MIX_B: Int = -1028477387

        private fun tableSize(capacity: Int): Int {
            require(capacity >= PROBE_SPAN) { "capacity must be at least $PROBE_SPAN, was $capacity" }
            var size = 1
            while (size < capacity) size = size shl 1
            return size
        }
    }
}
