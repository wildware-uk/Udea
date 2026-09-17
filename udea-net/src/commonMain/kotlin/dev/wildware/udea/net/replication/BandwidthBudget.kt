package dev.wildware.udea.net.replication

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.transport.PeerId

/**
 * Which entities a client is allowed to know about, and how much each one matters.
 *
 * Relevancy itself belongs to its own issue; this is the seam that lets the send loop land
 * first. [ALL_VISIBLE] answers "everything, equally", which is correct for a small test world
 * and is exactly what the packing and starvation tests need.
 */
public interface RelevancySet {

    /** Whether [client] may be told about [netId] at all. */
    public fun isRelevant(client: PeerId, netId: NetId): Boolean

    /**
     * How much [netId] matters to [client], as a multiplier on its priority growth.
     *
     * Named "distance weight" after the Source-style accumulator it feeds: a hero in view grows
     * priority fast, a minion across the map grows it slowly and is therefore updated rarely but
     * never starved, because its priority still climbs.
     */
    public fun weightOf(client: PeerId, netId: NetId): Float

    public companion object {

        /** Everything is relevant, at weight one. The behaviour until relevancy lands. */
        public val ALL_VISIBLE: RelevancySet = object : RelevancySet {
            override fun isRelevant(client: PeerId, netId: NetId): Boolean = true
            override fun weightOf(client: PeerId, netId: NetId): Float = 1f
        }
    }
}

/**
 * The Source-style priority accumulator: `priority += base * ticksSinceSent * distanceWeight`.
 *
 * ## Why an accumulator rather than a round robin
 *
 * A round robin is fair and useless: it spends the same bytes on a minion behind a wall as on
 * the hero the player is fighting. A pure importance sort is useful and unfair: a low-weight
 * entity that never wins the sort is never sent at all, and its client's copy diverges forever.
 * Multiplying importance by *time since last sent* gives both — the hero wins most ticks, and
 * the minion's priority climbs until it wins one, which puts a **bound** on staleness that a
 * test can assert (issue #107's starvation bound).
 *
 * The counterexample the old code is: `NetworkServerSystem.kt:110` sent every entity to every
 * client every tick, so there was no bound to state because there was no selection at all — the
 * bandwidth simply grew with the entity count until the link gave out.
 */
public class PriorityAccumulator(

    /** Growth per tick per unit weight, before the time multiplier. */
    public val base: Float = DEFAULT_BASE,
) {

    /**
     * Adds this tick's growth for [netId] to [state].
     *
     * @return the entity's priority after the addition.
     */
    public fun accumulate(
        state: ClientReplicationState,
        netId: NetId,
        tick: Tick,
        weight: Float,
    ): Float {
        val ticksSinceSent = state.ticksSinceSent(netId, tick)
        state.addPriority(netId, base * ticksSinceSent * weight)
        return state.priorityOf(netId)
    }

    public companion object {

        /** One unit per tick per unit weight. Only the ratios between entities matter. */
        public const val DEFAULT_BASE: Float = 1f
    }
}

/**
 * Orders candidate entities by descending priority without allocating.
 *
 * A binary max-heap over parallel arrays, rebuilt per client per tick from arrays that are
 * reused for the life of the session. Heapify is `O(n)` and each pop is `O(log n)`, and the
 * packer stops popping the moment the datagram is full — so a 300-entity world where twenty
 * entities fit costs `300 + 20 log 300`, not a full sort of 300.
 */
public class PrioritySelector(initialCapacity: Int = 64) {

    private var netIds = IntArray(initialCapacity)
    private var keys = FloatArray(initialCapacity)

    /** Candidates currently held. */
    public var size: Int = 0
        private set

    /** Drops every candidate, keeping the arrays. */
    public fun clear() {
        size = 0
    }

    /** Offers [netId] with priority [key]. */
    public fun add(netId: NetId, key: Float) {
        if (size == netIds.size) {
            netIds = netIds.copyOf(size * 2)
            keys = keys.copyOf(size * 2)
        }
        netIds[size] = netId.raw
        keys[size] = key
        size++
    }

    /** Arranges the candidates into a max-heap. Call once, after the last [add]. */
    public fun heapify() {
        for (index in (size / 2 - 1) downTo 0) siftDown(index)
    }

    /**
     * Removes and returns the highest-priority candidate.
     *
     * @throws NoSuchElementException when empty — a packer that pops without checking [size] has
     *   a bug, and returning [NetId.NONE] would hide it behind an entity that does not exist.
     */
    public fun poll(): NetId {
        if (size == 0) throw NoSuchElementException("no candidates left to pack")
        val top = netIds[0]
        size--
        if (size > 0) {
            netIds[0] = netIds[size]
            keys[0] = keys[size]
            siftDown(0)
        }
        return NetId.ofRaw(top)
    }

    private fun siftDown(from: Int) {
        var parent = from
        while (true) {
            val left = parent * 2 + 1
            if (left >= size) return
            val right = left + 1
            // Ties break on the lower NetId so two runs of the same scenario pack in the same
            // order and produce byte-identical packets. Float equality is the right comparison
            // here: two priorities that are the same float must not order at random.
            var largest = if (right < size && better(right, left)) right else left
            if (!better(largest, parent)) return
            swap(parent, largest)
            parent = largest
        }
    }

    private fun better(a: Int, b: Int): Boolean =
        keys[a] > keys[b] || (keys[a] == keys[b] && netIds[a] < netIds[b])

    private fun swap(a: Int, b: Int) {
        val netId = netIds[a]
        netIds[a] = netIds[b]
        netIds[b] = netId
        val key = keys[a]
        keys[a] = keys[b]
        keys[b] = key
    }
}

/**
 * How many payload bytes one client's datagram may spend this tick.
 *
 * A budget rather than a hope. `NetworkServerSystem.kt:110` had none: it called `sendToAllUDP`
 * from inside an `IteratingSystem` body, so the wire cost was `entities x clients x tick rate`
 * with nothing capping it. Here the number is decided before a byte is written, the packer stops
 * when it is spent, and everything that did not fit keeps its accumulated priority and wins the
 * next tick.
 */
public class BandwidthBudget(

    /** Payload bytes per datagram, before frame and header overhead. */
    public val bytesPerPacket: Int = DEFAULT_BYTES_PER_PACKET,
) {

    init {
        require(bytesPerPacket > 0) { "bytesPerPacket must be positive, was $bytesPerPacket" }
    }

    public companion object {

        /**
         * 1200 bytes: one datagram that fits an IPv6 path MTU without fragmenting.
         *
         * At 60Hz with one datagram per client per tick this is a 72 KB/s ceiling; the send loop
         * spends well under it in practice because a quiet entity costs nothing at all.
         */
        public const val DEFAULT_BYTES_PER_PACKET: Int = 1200
    }
}
