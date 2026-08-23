package dev.wildware.udea.net.replication

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.PacketHeader

/**
 * Everything the server remembers about one client, and deliberately not one byte more.
 *
 * Per client: a `LongArray` of acked baseline ticks indexed by `NetId.index`, a `FloatArray` of
 * accumulated priority, a small ring of in-flight packet records, and two integers. **No shadow
 * copy of world state.** Spec section 7's risk row puts a naive per-client shadow world at about
 * 25MB at MOBA scale, which does not fit L2 and turns diffing into a memory-bandwidth problem;
 * the snapshot ring is already the baseline store (spec 3.1), so a client's baseline is a *tick
 * number* naming a ring slot, not a copy of anything.
 *
 * The 65 536-entry worst case for the index arrays never materialises either: they grow to the
 * highest `NetId.index` actually seen, which for a session is the entity high-water mark.
 */
public class ClientReplicationState(

    /** Which client this is. */
    public val peer: PeerId,

    initialIndices: Int = INITIAL_INDICES,
) {

    /** Per `NetId.index`: the newest server tick this client has acked holding that entity. */
    private var baselineTicks = LongArray(initialIndices) { NO_BASELINE }

    /** Per `NetId.index`: the generation the baseline was recorded for. */
    private var baselineGenerations = IntArray(initialIndices) { -1 }

    /** Per `NetId.index`: accumulated send priority, reset to zero when the entity is sent. */
    private var priorities = FloatArray(initialIndices)

    /** Per `NetId.index`: the server tick the entity was last packed into a datagram at. */
    private var lastSentTicks = LongArray(initialIndices) { NO_BASELINE }

    /**
     * Per `NetId.index`: [TRACKED], [DESTROY_PENDING] or [RETIRED], for the generation in
     * [baselineGenerations].
     *
     * Three states and not a boolean, and the third one is load-bearing. A `Destroy` is written
     * every tick until it is acknowledged — so several packets carry it, and several acks for it
     * arrive. Without [RETIRED], the second of those acks looks exactly like the first ack of a
     * brand new entity at that index: it re-tracks the corpse, the server writes the `Destroy`
     * again, and the index never becomes free. The entity is then never replaced, which presents
     * much later as "the respawned unit never appears on the client".
     */
    private var slotStates = ByteArray(initialIndices)

    private val records = Array(RECORD_RING) { SentPacket() }

    /** The next sequence number this client's packets will carry. */
    public var nextSeq: Int = 0
        private set

    /** Highest sequence received *from* this client, for the ack field of outgoing packets. */
    public var remoteSeq: Int = -1
        private set

    /** Bit `n` set means sequence `remoteSeq - 1 - n` was also received from this client. */
    public var remoteAckBits: Int = 0
        private set

    /** Newest server tick this client has acknowledged receiving anything for. */
    public var lastAckedTick: Tick = Tick.ZERO
        private set

    /** Sequences acknowledged since the state was created. */
    public var ackedPackets: Long = 0L
        private set

    /** Sequences that left and were never acknowledged before they aged out of the ring. */
    public var lostPackets: Long = 0L
        private set

    /** The baseline tick for [netId], or [NO_BASELINE] when this client has never acked it. */
    public fun baselineTickOf(netId: NetId): Long {
        val index = netId.index
        if (index >= baselineTicks.size) return NO_BASELINE
        // A recycled index is a different entity. Reporting the old occupant's baseline would
        // delta-encode a brand new entity against a stranger's state, which decodes cleanly and
        // is wrong in every field: exactly the aliasing NetId's generation byte exists to stop.
        if (baselineGenerations[index] != netId.generation) return NO_BASELINE
        if (slotStates[index] != TRACKED) return NO_BASELINE
        return baselineTicks[index]
    }

    /** Accumulated priority for [netId]. */
    public fun priorityOf(netId: NetId): Float =
        if (netId.index >= priorities.size) 0f else priorities[netId.index]

    /** How many ticks since [netId] was last packed, or [tick] itself when it never has been. */
    public fun ticksSinceSent(netId: NetId, tick: Tick): Long {
        val index = netId.index
        if (index >= lastSentTicks.size || lastSentTicks[index] == NO_BASELINE) return tick.value + 1L
        return tick.value - lastSentTicks[index]
    }

    /** Adds [amount] to [netId]'s priority. Called once per relevant entity per tick. */
    public fun addPriority(netId: NetId, amount: Float) {
        val index = netId.index
        ensureIndices(index + 1)
        priorities[index] += amount
    }

    /** Records that [netId] rode packet [seq], sent at [tick]. Zeroes its accumulated priority. */
    public fun recordSent(netId: NetId, seq: Int, tick: Tick) {
        val index = netId.index
        ensureIndices(index + 1)
        priorities[index] = 0f
        lastSentTicks[index] = tick.value
        record(seq).add(netId)
    }

    /** Opens a record for an outgoing packet and returns its sequence number. */
    public fun beginPacket(tick: Tick): Int {
        val seq = nextSeq
        nextSeq = (nextSeq + 1) and PacketHeader.SEQ_MASK
        val slot = record(seq)
        // The slot being reused belonged to a packet 64 sequences ago. If it was never acked it
        // never will be, and counting it here is what makes `lostPackets` a real measurement
        // rather than a counter that only ever sees the losses the simulation announced.
        if (slot.inUse && !slot.acked) lostPackets++
        slot.reset(seq, tick)
        return seq
    }

    /**
     * Applies an ack field from the client: [ack] plus the 32 sequences [ackBits] describes.
     *
     * Each newly acknowledged packet promotes the baseline of every entity it carried, which is
     * the whole of the per-(client, entity) baseline bookkeeping: no message, no timer, no
     * retransmit queue. An entity whose create was lost simply keeps [NO_BASELINE] and is
     * written in full again next time it is packed.
     */
    public fun applyAck(ack: Int, ackBits: Int) {
        acknowledge(ack)
        for (bit in 0 until ACK_BITS) {
            if (ackBits ushr bit and 1 == 0) continue
            acknowledge((ack - 1 - bit) and PacketHeader.SEQ_MASK)
        }
    }

    /** Records that sequence [seq] arrived from this client, updating [remoteSeq]/[remoteAckBits]. */
    public fun onReceived(seq: Int) {
        if (remoteSeq < 0) {
            remoteSeq = seq
            remoteAckBits = 0
            return
        }
        if (PacketHeader.isNewer(seq, remoteSeq)) {
            val shift = sequenceDistance(seq, remoteSeq)
            remoteAckBits = if (shift >= ACK_BITS) 0 else (remoteAckBits shl shift) or (1 shl (shift - 1))
            remoteSeq = seq
        } else {
            val back = sequenceDistance(remoteSeq, seq)
            if (back in 1..ACK_BITS) remoteAckBits = remoteAckBits or (1 shl (back - 1))
        }
    }

    /**
     * Zeroes [netId]'s accumulated priority without recording a send.
     *
     * For the entity that won the selection and then turned out to have nothing to say: it is up
     * to date, so leaving its priority climbing would make it win every subsequent tick too and
     * push genuinely stale entities down the heap for nothing.
     */
    public fun clearPriority(netId: NetId) {
        val index = netId.index
        if (index < priorities.size) priorities[index] = 0f
    }

    /** How many `NetId.index` values this state has arrays for. Iteration bound for removals. */
    public val trackedIndices: Int get() = baselineGenerations.size

    /**
     * The generation this client last acked at [index], or `-1` when it knows of no entity there.
     *
     * The server walks this to find entities the client believes in that no longer exist, which
     * is how a `Destroy` is generated without keeping a second roster.
     */
    public fun trackedGeneration(index: Int): Int = when {
        index >= baselineGenerations.size -> -1
        slotStates[index] == RETIRED -> -1
        else -> baselineGenerations[index]
    }

    /** Marks [netId]'s `Destroy` as written and awaiting an ack. */
    public fun markDestroyPending(netId: NetId) {
        val index = netId.index
        ensureIndices(index + 1)
        if (baselineGenerations[index] == netId.generation && slotStates[index] == TRACKED) {
            slotStates[index] = DESTROY_PENDING
        }
    }

    /** Whether a `Destroy` for [index] is written and unacknowledged. */
    public fun isDestroyPending(index: Int): Boolean =
        index < slotStates.size && slotStates[index] == DESTROY_PENDING

    /** Forgets [netId] entirely: called when the entity is destroyed and its index may recycle. */
    public fun forget(netId: NetId) {
        val index = netId.index
        if (index >= baselineTicks.size) return
        baselineTicks[index] = NO_BASELINE
        baselineGenerations[index] = -1
        priorities[index] = 0f
        lastSentTicks[index] = NO_BASELINE
        slotStates[index] = TRACKED
    }

    private fun acknowledge(seq: Int) {
        val slot = records[seq and RECORD_MASK]
        if (!slot.inUse || slot.seq != seq || slot.acked) return
        slot.acked = true
        ackedPackets++
        if (slot.tick > lastAckedTick) lastAckedTick = slot.tick
        for (position in 0 until slot.count) {
            val netId = NetId.ofRaw(slot.netIds[position])
            val index = netId.index
            ensureIndices(index + 1)
            if (baselineGenerations[index] != netId.generation) {
                // A different occupant of this index: a genuinely new entity, whatever the old
                // one's fate was.
                baselineGenerations[index] = netId.generation
                baselineTicks[index] = slot.tick.value
                slotStates[index] = TRACKED
                continue
            }
            when (slotStates[index]) {
                // The client has confirmed the removal. The index may now be reused, and every
                // later ack naming this generation is a duplicate confirmation to be ignored.
                DESTROY_PENDING -> {
                    slotStates[index] = RETIRED
                    baselineTicks[index] = NO_BASELINE
                }

                RETIRED -> Unit

                else -> if (slot.tick.value > baselineTicks[index]) baselineTicks[index] = slot.tick.value
            }
        }
    }

    private fun record(seq: Int): SentPacket = records[seq and RECORD_MASK]

    private fun ensureIndices(required: Int) {
        if (required <= baselineTicks.size) return
        var capacity = baselineTicks.size
        while (capacity < required) capacity *= 2
        baselineTicks = baselineTicks.copyOf(capacity).also { it.fill(NO_BASELINE, baselineTicks.size, capacity) }
        baselineGenerations = baselineGenerations.copyOf(capacity).also { it.fill(-1, baselineGenerations.size, capacity) }
        priorities = priorities.copyOf(capacity)
        lastSentTicks = lastSentTicks.copyOf(capacity).also { it.fill(NO_BASELINE, lastSentTicks.size, capacity) }
        slotStates = slotStates.copyOf(capacity)
    }

    /** One in-flight packet: which entities it carried, so an ack can promote their baselines. */
    private class SentPacket {
        var seq: Int = -1
        var tick: Tick = Tick.ZERO
        var acked: Boolean = false
        var inUse: Boolean = false
        var netIds: IntArray = IntArray(32)
        var count: Int = 0

        fun reset(seq: Int, tick: Tick) {
            this.seq = seq
            this.tick = tick
            acked = false
            inUse = true
            count = 0
        }

        fun add(netId: NetId) {
            if (count == netIds.size) netIds = netIds.copyOf(netIds.size * 2)
            netIds[count++] = netId.raw
        }
    }

    public companion object {

        /** "This client has never acknowledged holding this entity." */
        public const val NO_BASELINE: Long = -1L

        /** Width of the `ackBits` field: one ack covers 33 packets. */
        public const val ACK_BITS: Int = 32

        /** In-flight records kept. Comfortably more than [ACK_BITS], so nothing is lost early. */
        public const val RECORD_RING: Int = 64
        private const val RECORD_MASK: Int = RECORD_RING - 1

        private const val INITIAL_INDICES: Int = 256

        /** The index holds an entity this client is being kept up to date about. */
        internal const val TRACKED: Byte = 0

        /** A `Destroy` for this index has been written and is waiting to be acknowledged. */
        internal const val DESTROY_PENDING: Byte = 1

        /** The client has confirmed the removal; the index is free for a new generation. */
        internal const val RETIRED: Byte = 2

        /** Forward distance from [older] to [newer] under 16-bit wraparound. */
        public fun sequenceDistance(newer: Int, older: Int): Int =
            (newer - older) and PacketHeader.SEQ_MASK
    }
}
