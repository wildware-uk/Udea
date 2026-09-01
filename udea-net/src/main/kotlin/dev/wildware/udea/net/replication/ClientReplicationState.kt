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

    /**
     * Per `NetId.index`: the tick the `Destroy` was first written at, while it is pending.
     *
     * An acknowledgement is for a *packet*, not for a record inside it, so "the client confirmed
     * the removal" has to mean "the client acked a packet sent at or after the tick the removal
     * started being written". Without that tick to compare against, an ack for an ordinary update
     * that left before the entity died retires the index on arrival - and at 300ms with
     * reordering, one of those is almost always still in flight. The `Destroy` then stops being
     * written, and if the datagram that carried it was one of the lost ones the client keeps the
     * corpse for the rest of the session.
     */
    private var destroyTicks = LongArray(initialIndices) { NO_BASELINE }

    /**
     * Per `NetId.index`: the ticks of packets carrying that entity that are **sent and not yet
     * acknowledged**, [PENDING_PER_INDEX] slots each, flat.
     *
     * This is the structure the convergence bug was missing. The server delta-encodes against
     * the newest *acked* packet; the client applies into a store holding the newest *applied*
     * one, and those are a round trip apart. A field that changes and changes back inside that
     * window equals the baseline again by the time the packer looks at it, is therefore omitted,
     * and the client keeps the intermediate value for ever. The states the client can possibly
     * be holding are exactly `{baseline} + these ticks`, and diffing against all of them is what
     * makes the omission provably safe rather than usually safe.
     *
     * Ticks, not copies: each one names a slot the snapshot ring already holds (spec 3.1), so
     * this costs 8 bytes per in-flight send and adds no second world.
     */
    private var pendingTicks = LongArray(initialIndices * PENDING_PER_INDEX)

    /** Per `NetId.index`: how many of [pendingTicks] are live, or [PENDING_OVERFLOW]. */
    private var pendingCounts = IntArray(initialIndices)

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

    /**
     * How many unacknowledged sends of [netId] are outstanding, or [PENDING_OVERFLOW].
     *
     * [PENDING_OVERFLOW] means the tracking ran out of room and the server must stop guessing:
     * write the entity in full. That is the same recovery a baseline that has aged out of the
     * ring takes, and it clears itself, because the ack for the full write empties the list.
     */
    public fun pendingSendCount(netId: NetId): Int {
        val index = netId.index
        if (index >= pendingCounts.size) return 0
        if (baselineGenerations[index] != netId.generation) return 0
        return pendingCounts[index]
    }

    /** The tick of unacknowledged send [position] of [netId]. */
    public fun pendingSendTick(netId: NetId, position: Int): Long =
        pendingTicks[netId.index * PENDING_PER_INDEX + position]

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

    /** Records that [netId]'s **state** rode packet [seq], sent at [tick]. Zeroes its priority. */
    public fun recordSent(netId: NetId, seq: Int, tick: Tick) {
        val index = netId.index
        ensureIndices(index + 1)
        priorities[index] = 0f
        lastSentTicks[index] = tick.value
        pushPending(index, tick.value)
        record(seq).add(netId, removal = false)
    }

    /**
     * Records that [netId]'s **removal** rode packet [seq], sent at [tick].
     *
     * Separate from [recordSent] because the two mean opposite things to [applyAck], and the ack
     * path cannot tell them apart from a [NetId] alone: one says the client is being given the
     * entity, the other says it is being told the entity is gone. Recording a removal as an
     * ordinary send is what let a late ack for a `Destroy` install a baseline for a corpse - see
     * the class KDoc of `RecycledIndexAckTest`.
     *
     * No tick is pushed onto the unacknowledged-send list either. That list is "states the client
     * might be holding", and a packet whose only mention of the entity was the record saying it
     * was gone is not one of them.
     */
    public fun recordRemovalSent(netId: NetId, seq: Int, tick: Tick) {
        val index = netId.index
        ensureIndices(index + 1)
        priorities[index] = 0f
        lastSentTicks[index] = tick.value
        record(seq).add(netId, removal = true)
    }

    /** Opens a record for an outgoing packet and returns its sequence number. */
    public fun beginPacket(tick: Tick): Int {
        val seq = nextSeq
        nextSeq = (nextSeq + 1) and PacketHeader.SEQ_MASK
        val slot = record(seq)
        // The slot being reused belonged to a packet 64 sequences ago. If it was never acked it
        // never will be, and counting it here is what makes `lostPackets` a real measurement
        // rather than a counter that only ever sees the losses the simulation announced.
        if (slot.inUse && !slot.acked) {
            lostPackets++
            // Sixty-four sequences with no acknowledgement, while every ack the client sent
            // repeated the 32 before it: this packet did not arrive, so its tick is not a state
            // the client can be holding and must stop being diffed against. Leaving it there
            // would pin every entity it carried to a permanent full-state write.
            forgetPending(slot)
        }
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

    /** Marks [netId]'s `Destroy` as written at [tick] and awaiting an ack. */
    public fun markDestroyPending(netId: NetId, tick: Tick) {
        val index = netId.index
        ensureIndices(index + 1)
        if (baselineGenerations[index] == netId.generation && slotStates[index] == TRACKED) {
            slotStates[index] = DESTROY_PENDING
            destroyTicks[index] = tick.value
        }
    }

    /** Whether a `Destroy` for [index] is written and unacknowledged. */
    public fun isDestroyPending(index: Int): Boolean =
        index < slotStates.size && slotStates[index] == DESTROY_PENDING

    /**
     * Whether [index]'s removal has been **confirmed** by the client.
     *
     * A retired slot is a slot the client provably does not hold. For a genuinely destroyed
     * entity that is the end of the story. For one the client merely stopped being allowed to
     * see - an `EntityOp.Leave` - it is not: the entity is still alive and may become visible
     * again, and the server then has to write it as a `Create` against no baseline. The ack path
     * deliberately never leaves `RETIRED` on its own (a duplicate confirmation must stay inert),
     * so the packer asks this and calls [forget] to start the entity over.
     */
    public fun isRetired(index: Int): Boolean =
        index < slotStates.size && slotStates[index] == RETIRED

    /** Forgets [netId] entirely: called when the entity is destroyed and its index may recycle. */
    public fun forget(netId: NetId) {
        val index = netId.index
        if (index >= baselineTicks.size) return
        baselineTicks[index] = NO_BASELINE
        baselineGenerations[index] = -1
        priorities[index] = 0f
        lastSentTicks[index] = NO_BASELINE
        slotStates[index] = TRACKED
        pendingCounts[index] = 0
        destroyTicks[index] = NO_BASELINE
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
            if (slot.removalAt(position)) {
                confirmRemoval(index, netId, slot.tick.value)
                continue
            }
            if (baselineGenerations[index] != netId.generation) {
                // A different occupant of this index: a genuinely new entity, whatever the old
                // one's fate was.
                baselineGenerations[index] = netId.generation
                baselineTicks[index] = slot.tick.value
                slotStates[index] = TRACKED
                prunePending(index, slot.tick.value)
                continue
            }
            prunePending(index, slot.tick.value)
            when (slotStates[index]) {
                // A state record cannot confirm a removal: no state is packed for an index whose
                // `Destroy` is outstanding, so anything arriving here left *before* the entity
                // died and proves nothing about the corpse. [confirmRemoval] is the only thing
                // that retires a slot.
                DESTROY_PENDING -> Unit

                RETIRED -> Unit

                else -> if (slot.tick.value > baselineTicks[index]) baselineTicks[index] = slot.tick.value
            }
        }
    }

    /**
     * Retires [index] because the client confirmed [netId]'s removal in a packet sent at [tick].
     *
     * Says nothing about any other generation. A removal confirmation for an index that has since
     * been handed to somebody else is simply spent: the corpse it names is gone from both ends,
     * and the occupant that replaced it has a tracking state of its own that this must not touch.
     * Treating one as evidence about the *current* occupant is what resurrected dead generations
     * and left an index removing them one per tick for the rest of the session.
     */
    private fun confirmRemoval(index: Int, netId: NetId, tick: Long) {
        if (baselineGenerations[index] != netId.generation) return
        if (slotStates[index] != DESTROY_PENDING) return
        // Only a packet that left at or after the removal started being written can have carried
        // it. An older one in flight proves nothing about the corpse.
        if (tick < destroyTicks[index]) return
        slotStates[index] = RETIRED
        baselineTicks[index] = NO_BASELINE
        destroyTicks[index] = NO_BASELINE
    }

    /** Appends [tick] to [index]'s unacknowledged-send list, or marks the list overflowed. */
    private fun pushPending(index: Int, tick: Long) {
        val count = pendingCounts[index]
        if (count == PENDING_OVERFLOW) return
        val base = index * PENDING_PER_INDEX
        if (count > 0 && pendingTicks[base + count - 1] == tick) return
        if (count == PENDING_PER_INDEX) {
            pendingCounts[index] = PENDING_OVERFLOW
            return
        }
        pendingTicks[base + count] = tick
        pendingCounts[index] = count + 1
    }

    /** Drops every unacknowledged-send tick at or before [tick]: it is the baseline, or older. */
    private fun prunePending(index: Int, tick: Long) {
        val count = pendingCounts[index]
        if (count <= 0) {
            // An overflowed list is restored by the ack for the full write it forced, and only
            // then: clearing it earlier would resume delta-encoding against an incomplete set.
            if (count == PENDING_OVERFLOW) pendingCounts[index] = 0
            return
        }
        val base = index * PENDING_PER_INDEX
        var kept = 0
        for (position in 0 until count) {
            val value = pendingTicks[base + position]
            if (value <= tick) continue
            pendingTicks[base + kept++] = value
        }
        pendingCounts[index] = kept
    }

    /** Drops [slot]'s tick from every entity it carried: that packet is lost, not merely late. */
    private fun forgetPending(slot: SentPacket) {
        val tick = slot.tick.value
        for (position in 0 until slot.count) {
            val index = NetId.ofRaw(slot.netIds[position]).index
            if (index >= pendingCounts.size) continue
            val count = pendingCounts[index]
            if (count <= 0) continue
            val base = index * PENDING_PER_INDEX
            var kept = 0
            for (entry in 0 until count) {
                val value = pendingTicks[base + entry]
                if (value == tick) continue
                pendingTicks[base + kept++] = value
            }
            pendingCounts[index] = kept
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
        destroyTicks = destroyTicks.copyOf(capacity).also { it.fill(NO_BASELINE, destroyTicks.size, capacity) }
        pendingTicks = pendingTicks.copyOf(capacity * PENDING_PER_INDEX)
        pendingCounts = pendingCounts.copyOf(capacity)
    }

    /** One in-flight packet: which entities it carried, so an ack can promote their baselines. */
    private class SentPacket {
        var seq: Int = -1
        var tick: Tick = Tick.ZERO
        var acked: Boolean = false
        var inUse: Boolean = false
        var netIds: IntArray = IntArray(32)

        /** Per entry: whether the record was a removal rather than state. */
        var removals: BooleanArray = BooleanArray(32)
        var count: Int = 0

        fun reset(seq: Int, tick: Tick) {
            this.seq = seq
            this.tick = tick
            acked = false
            inUse = true
            count = 0
        }

        fun add(netId: NetId, removal: Boolean) {
            if (count == netIds.size) {
                netIds = netIds.copyOf(netIds.size * 2)
                removals = removals.copyOf(removals.size * 2)
            }
            removals[count] = removal
            netIds[count++] = netId.raw
        }

        fun removalAt(position: Int): Boolean = removals[position]
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

        /**
         * Unacknowledged sends tracked per entity before the server gives up and writes in full.
         *
         * One send per tick per entity is the ceiling, so this is a round trip measured in
         * ticks: 32 is half a second at 60Hz, comfortably past the 150ms and 300ms links the
         * proof runs. Past it the entity goes full-state, which costs bandwidth and is always
         * correct.
         */
        public const val PENDING_PER_INDEX: Int = 32

        /** "More unacknowledged sends than [PENDING_PER_INDEX]; the set is no longer known." */
        public const val PENDING_OVERFLOW: Int = -1

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
