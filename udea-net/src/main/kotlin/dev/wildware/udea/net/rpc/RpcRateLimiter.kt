package dev.wildware.udea.net.rpc

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.transport.PeerId

/**
 * A token bucket per (connection, RPC), advanced by [Tick] and by nothing else.
 *
 * ## Why rate limiting is here at all
 *
 * Decision D10 puts public-internet hardening in this phase rather than retrofitting it onto a
 * live wire format, and an authority guard alone does not survive contact with the internet: a
 * client that legitimately owns its champion can still send its own activation ten thousand
 * times a second, and every one of those passes the ownership check. The guard answers "may
 * you", the bucket answers "this often".
 *
 * ## Ticks, not milliseconds
 *
 * The obvious implementation is `System.nanoTime()` and it is the wrong one twice over: it
 * makes the server's acceptance of a packet depend on how fast the machine happens to be, and
 * it makes a seeded soak unreproducible — the same recorded traffic refuses different calls on
 * different runs, so a rate-limiting bug cannot be bisected. `NoWallClockInTransportTest`
 * already forbids the clock below this layer; this holds the same line above it.
 *
 * All arithmetic is integer. Tokens are counted in *tick-units*: a call costs [ticksPerSecond]
 * of them and each elapsed tick mints [RpcRate.perSecond], so a bucket refills at exactly the
 * declared rate with no float, no accumulated drift and no rounding that differs by platform.
 *
 * ## Storage
 *
 * A flat `LongArray` indexed by `peer * rpcCount + rpc`, grown on demand. [PeerId] is a dense
 * small integer for exactly this reason, and a `HashMap` keyed by a boxed pair would allocate
 * on the path a flood is trying to make expensive.
 */
public class RpcRateLimiter(

    /** The simulation's tick rate. The unit every bucket is denominated in. */
    public val ticksPerSecond: Int,

    /** How many RPCs the registry holds; the row width of the bucket table. */
    private val rpcCount: Int,
) {

    init {
        require(ticksPerSecond > 0) { "ticksPerSecond must be positive, was $ticksPerSecond" }
        require(rpcCount >= 0) { "rpcCount must not be negative, was $rpcCount" }
    }

    /** Tokens held, in tick-units. `-1` marks a bucket that has never been touched. */
    private var tokens: LongArray = LongArray(0)

    /** The tick each bucket was last refilled at. */
    private var lastTick: LongArray = LongArray(0)

    /**
     * Whether [sender] may make this call now, spending a token if so.
     *
     * An unlimited [RpcRate] short-circuits before touching the table, so declaring no rate
     * costs nothing at all rather than costing a full bucket per connection per RPC.
     */
    public fun allow(sender: PeerId, rpcIndex: Int, rate: RpcRate, now: Tick): Boolean {
        if (rate.isUnlimited) return true
        val slot = slotOf(sender, rpcIndex)
        val capacity = rate.effectiveBurst.toLong() * ticksPerSecond
        val cost = ticksPerSecond.toLong()
        if (tokens[slot] < 0L) {
            // First call from this connection on this RPC: a full bucket, so a player who
            // presses a key one tick after connecting is not refused for having no history.
            tokens[slot] = capacity
            lastTick[slot] = now.value
        }
        val elapsed = now.value - lastTick[slot]
        if (elapsed > 0L) {
            // Clamped to capacity, so a connection that sat idle for ten minutes gets a burst
            // and not ten minutes of credit.
            val refilled = tokens[slot] + elapsed * rate.perSecond
            tokens[slot] = if (refilled > capacity) capacity else refilled
            lastTick[slot] = now.value
        }
        if (tokens[slot] < cost) return false
        tokens[slot] -= cost
        return true
    }

    /** Forgets [sender]'s buckets. Called when a connection drops so a reused id starts clean. */
    public fun forget(sender: PeerId) {
        val base = sender.raw * rpcCount
        if (base + rpcCount > tokens.size) return
        for (i in base until base + rpcCount) tokens[i] = UNTOUCHED
    }

    private fun slotOf(sender: PeerId, rpcIndex: Int): Int {
        require(rpcIndex in 0 until rpcCount) { "rpc index $rpcIndex is outside 0..<$rpcCount" }
        require(sender.raw >= 0) { "$sender has no bucket row" }
        val slot = sender.raw * rpcCount + rpcIndex
        if (slot >= tokens.size) grow(slot + 1)
        return slot
    }

    private fun grow(minimum: Int) {
        val previous = tokens.size
        var size = if (previous == 0) rpcCount.coerceAtLeast(1) else previous
        while (size < minimum) size *= 2
        val grown = tokens.copyOf(size)
        // `copyOf` zero-fills, and zero is a legitimate bucket state - "empty, refuse". The new
        // rows have to read as UNTOUCHED instead, or every connection's first call is refused.
        for (i in previous until size) grown[i] = UNTOUCHED
        tokens = grown
        lastTick = lastTick.copyOf(size)
    }

    private companion object {
        /** A bucket nobody has called yet. Distinguishable from "empty", which is a refusal. */
        const val UNTOUCHED: Long = -1L
    }
}
