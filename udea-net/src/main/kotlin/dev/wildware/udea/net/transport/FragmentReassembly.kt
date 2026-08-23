package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick

/**
 * One message being rebuilt from the fragments of it that have arrived.
 *
 * Fragments arrive out of order, some never arrive at all, and a hostile peer will happily
 * open a fragment it never finishes. So an assembly is a *bounded, expiring* thing: fixed
 * capacity decided at construction, a bitmask of which indices have landed, and a deadline
 * after which it is thrown away whether or not it completed.
 *
 * Every fragment but the last is exactly [fragmentBytes] long, which is what lets an index map
 * to a byte offset by multiplication. It is also a validation: a peer that sends a short
 * middle fragment is telling this assembly to write somewhere the sender did not intend, and
 * is refused instead.
 */
internal class FragmentAssembly(
    maxFragments: Int,
    private val fragmentBytes: Int,
) {

    /** The message this is rebuilding, or [UNUSED]. */
    var messageId: Int = UNUSED
        private set

    /** How many fragments the sender said there are. */
    var expectedCount: Int = 0
        private set

    /** Tick after which this is abandoned. */
    var deadline: Tick = Tick.ZERO
        private set

    /** Bit `i` set means fragment `i` has landed. Sized by [MAX_FRAGMENTS_LIMIT]. */
    private var receivedMask: Long = 0L

    private var receivedCount: Int = 0
    private var lastFragmentBytes: Int = 0

    /** The rebuilt message. Valid up to [totalBytes] once [isComplete]. */
    val payload: ByteArray = ByteArray(maxFragments * fragmentBytes)

    /** Bytes of [payload] that are the message. */
    var totalBytes: Int = 0
        private set

    /** Whether every fragment has landed. */
    val isComplete: Boolean get() = messageId != UNUSED && receivedCount == expectedCount

    /** Whether this slot is holding a message that has not completed. */
    val isActive: Boolean get() = messageId != UNUSED

    /** Starts rebuilding [messageId], discarding anything this slot held. */
    fun begin(messageId: Int, expectedCount: Int, deadline: Tick) {
        this.messageId = messageId
        this.expectedCount = expectedCount
        this.deadline = deadline
        receivedMask = 0L
        receivedCount = 0
        lastFragmentBytes = 0
        totalBytes = 0
    }

    /**
     * Copies one fragment in.
     *
     * @return false when the fragment contradicts what this assembly was told, which is a
     *   refusal rather than an exception because it is exactly what a hostile peer sends.
     */
    fun accept(index: Int, source: ByteArray, offset: Int, length: Int): Boolean {
        if (index < 0 || index >= expectedCount) return false
        val isLast = index == expectedCount - 1
        if (isLast) {
            if (length > fragmentBytes) return false
        } else if (length != fragmentBytes) {
            // A short middle fragment would leave a hole the assembly would then report as
            // message bytes. Refusing is the only answer that cannot produce a torn message.
            return false
        }
        val bit = 1L shl index
        if (receivedMask and bit != 0L) return true
        System.arraycopy(source, offset, payload, index * fragmentBytes, length)
        receivedMask = receivedMask or bit
        receivedCount++
        if (isLast) lastFragmentBytes = length
        if (receivedCount == expectedCount) {
            totalBytes = (expectedCount - 1) * fragmentBytes + lastFragmentBytes
        }
        return true
    }

    /** Frees the slot. The buffer is kept: it is the pool. */
    fun release() {
        messageId = UNUSED
        receivedMask = 0L
        receivedCount = 0
        totalBytes = 0
    }

    companion object {

        /** No message is being rebuilt here. */
        const val UNUSED: Int = -1

        /** A `Long` bitmask holds 64 fragments and no more. */
        const val MAX_FRAGMENTS_LIMIT: Int = 64
    }
}

/**
 * The fixed set of part-built messages one connection is allowed to have open at once.
 *
 * The bound is the whole point. Reassembly is the classic memory-amplification bug in a UDP
 * stack: a peer sends fragment 3 of 16 with a fresh message id, over and over, and a receiver
 * that allocates per message id hands its whole heap over for the cost of a few hundred
 * datagrams. Here the peer gets [maxAssemblies] buffers, ever, and each of them expires.
 */
internal class FragmentReassembler(
    private val fragmentBytes: Int,
    private val maxAssemblies: Int = DEFAULT_MAX_ASSEMBLIES,
    private val maxFragments: Int = DEFAULT_MAX_FRAGMENTS,
    private val timeoutTicks: Long = DEFAULT_TIMEOUT_TICKS,
) {

    init {
        require(maxAssemblies >= 1) { "maxAssemblies must be >= 1, was $maxAssemblies" }
        require(maxFragments in 2..FragmentAssembly.MAX_FRAGMENTS_LIMIT) {
            "maxFragments must be in 2..${FragmentAssembly.MAX_FRAGMENTS_LIMIT}, was $maxFragments"
        }
        require(timeoutTicks >= 1L) { "timeoutTicks must be >= 1, was $timeoutTicks" }
    }

    private val slots = arrayOfNulls<FragmentAssembly>(maxAssemblies)

    /** Part-built messages abandoned because their deadline passed. */
    var timedOut: Long = 0L
        private set

    /** Part-built messages thrown away to make room for a newer one. */
    var evicted: Long = 0L
        private set

    /** Fragments refused because they contradicted their own header. */
    var refused: Long = 0L
        private set

    /** How many buffers have ever been allocated. Flat once a link is warm. */
    var allocatedAssemblies: Int = 0
        private set

    /** The largest message this reassembler will ever hand back, in bytes. */
    val maxMessageBytes: Int get() = maxFragments * fragmentBytes

    /**
     * Takes one fragment.
     *
     * @return the assembly when this fragment completed a message, else null. The returned
     *   assembly's buffer is valid until the caller calls [release] on it.
     */
    fun accept(
        messageId: Int,
        index: Int,
        count: Int,
        source: ByteArray,
        offset: Int,
        length: Int,
        now: Tick,
    ): FragmentAssembly? {
        if (count < 2 || count > maxFragments) {
            refused++
            return null
        }
        val slot = slotFor(messageId, count, now)
        if (!slot.accept(index, source, offset, length)) {
            refused++
            // The message is now untrustworthy in a way a later fragment cannot repair, so the
            // slot goes back rather than sitting there until its deadline.
            slot.release()
            return null
        }
        return if (slot.isComplete) slot else null
    }

    /** Releases a completed assembly the caller has finished reading. */
    fun release(assembly: FragmentAssembly) {
        assembly.release()
    }

    /** Abandons every assembly whose deadline has passed. Called once a tick. */
    fun expire(now: Tick) {
        for (slot in slots) {
            if (slot == null || !slot.isActive) continue
            if (now > slot.deadline) {
                timedOut++
                slot.release()
            }
        }
    }

    /** Abandons everything. Used when a connection ends. */
    fun clear() {
        for (slot in slots) slot?.release()
    }

    private fun slotFor(messageId: Int, count: Int, now: Tick): FragmentAssembly {
        var free = NO_SLOT
        var stalest = NO_SLOT
        var stalestDeadline = Long.MAX_VALUE
        for (index in slots.indices) {
            val slot = slots[index]
            if (slot == null || !slot.isActive) {
                if (free == NO_SLOT) free = index
                continue
            }
            if (slot.messageId == messageId) {
                // Two different fragment counts under one id: the id space wrapped onto a
                // message still in flight, or the peer is lying. Either way the older claim
                // cannot be finished, so the newer one takes the slot.
                if (slot.expectedCount != count) slot.begin(messageId, count, now + timeoutTicks)
                return slot
            }
            if (slot.deadline.value < stalestDeadline) {
                stalestDeadline = slot.deadline.value
                stalest = index
            }
        }
        if (free != NO_SLOT) {
            var slot = slots[free]
            if (slot == null) {
                slot = FragmentAssembly(maxFragments, fragmentBytes)
                slots[free] = slot
                allocatedAssemblies++
            }
            slot.begin(messageId, count, now + timeoutTicks)
            return slot
        }
        val victim = checkNotNull(slots[stalest]) { "every slot was active but none was stalest" }
        evicted++
        victim.begin(messageId, count, now + timeoutTicks)
        return victim
    }

    companion object {

        /**
         * Two messages in flight at once.
         *
         * Fragmentation is rare by design: a snapshot datagram is built to fit the MTU, so the
         * only routinely fragmented message is the protocol advert at connect. Two covers a
         * reordered pair; a third concurrent message means something above is misusing the
         * transport, and it should be visible as an eviction rather than absorbed.
         */
        const val DEFAULT_MAX_ASSEMBLIES: Int = 2

        /** 16 fragments of an MTU is roughly 19KB, comfortably over the largest advert. */
        const val DEFAULT_MAX_FRAGMENTS: Int = 16

        /** 60 ticks: one second at 60Hz, several times the round trip of any playable link. */
        const val DEFAULT_TIMEOUT_TICKS: Long = 60L

        private const val NO_SLOT: Int = -1
    }
}
