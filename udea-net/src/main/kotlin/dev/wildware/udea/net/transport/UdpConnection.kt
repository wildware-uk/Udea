package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.wire.PacketHeader
import java.net.InetSocketAddress
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One live link: who is at the other end, what has been seen, and how long the trip takes.
 *
 * Created only when a peer has proved it can receive at the address it claims (see
 * [ConnectionSecret]), which is why every field here is safe to allocate: an unverified peer
 * never reaches this type.
 *
 * ## The received-sequence window does three jobs with one integer
 *
 * [remoteAckBits] is simultaneously the acknowledgement this end will send back, the record of
 * which recent sequences have arrived, and therefore the replay filter. A packet whose sequence
 * is already set in the window is a duplicate — whether the network duplicated it or an
 * attacker recorded and re-sent it — and is refused. Keeping one structure rather than two is
 * not brevity: two would be two things that can disagree about whether a packet was seen.
 */
internal class UdpConnection(

    /** The peer id the layers above address. */
    val peer: PeerId,

    /** Where datagrams for this connection go, and the only address they are accepted from. */
    val address: InetSocketAddress,

    /** The value that must be in every payload datagram of this connection. */
    val salt: Long,

    /** The counters the [Transport] SPI hands out for this peer. */
    val stats: TransportStats,

    /** Body bytes one fragment carries, so reassembly can turn an index into an offset. */
    fragmentBytes: Int,

    /** Tick the connection opened. Seeds the idle timers. */
    createdAt: Tick,

    /** Lower bound on the retransmit timeout, in ticks. */
    private val minRtoTicks: Long,

    /** Upper bound on the retransmit timeout, in ticks. */
    private val maxRtoTicks: Long,
) {

    /** Sequence the next outgoing datagram will carry. */
    var localSeq: Int = 0
        private set

    /** Highest sequence seen from the peer, or [NO_SEQ] before the first. */
    var remoteSeq: Int = NO_SEQ
        private set

    /** Bit `n` set means `remoteSeq - 1 - n` has been seen. */
    var remoteAckBits: Int = 0
        private set

    /** Tick of the last datagram accepted from the peer. Drives the idle timeout. */
    var lastReceivedAt: Tick = createdAt
        private set

    /** Tick of the last datagram sent. Drives the keep-alive. */
    var lastSentAt: Tick = createdAt
        private set

    /** Smoothed round trip in ticks, or negative before the first sample. */
    var smoothedRttTicks: Float = UNMEASURED
        private set

    /** Round-trip variance in ticks, per RFC 6298. */
    var rttVarianceTicks: Float = 0f
        private set

    /** Datagrams refused because their sequence had already been seen. */
    var replayDropped: Long = 0L
        private set

    /** Datagrams refused because their sequence was older than the window can vouch for. */
    var staleDropped: Long = 0L
        private set

    /** Where a fragmented message is rebuilt. Bounded and expiring by construction. */
    val reassembler: FragmentReassembler = FragmentReassembler(fragmentBytes)

    private var nextMessageId: Int = 0

    private val sentSeq = IntArray(SENT_RING) { NO_SEQ }
    private val sentAt = LongArray(SENT_RING)

    /**
     * The retransmit timeout, in ticks: `srtt + 4 * rttvar`, clamped.
     *
     * ## What this transport does and does not retransmit
     *
     * Only the handshake. A payload datagram is never re-sent, and that is a design decision
     * rather than a gap: the payload is a delta against a baseline the receiver has
     * acknowledged, so by the time an RTO fires, the *newer* delta the sender is about to
     * produce is both smaller and more useful than the stale one. Retransmitting it would put
     * a second, slower recovery mechanism next to the one the replication layer already has,
     * and the two would fight over the same bandwidth budget.
     *
     * So the estimate exists to time handshake retries and to be read by the layer above as
     * the link's round trip, not to drive a reliability layer this transport does not have.
     */
    val rtoTicks: Long
        get() {
            if (smoothedRttTicks < 0f) return minRtoTicks
            val raw = ceil(smoothedRttTicks + RTT_VARIANCE_WEIGHT * rttVarianceTicks).toLong()
            return min(maxRtoTicks, max(minRtoTicks, raw))
        }

    /** Takes the next outgoing sequence and records when it went out, for the RTT sample. */
    fun beginSend(now: Tick): Int {
        val seq = localSeq
        localSeq = (localSeq + 1) and PacketHeader.SEQ_MASK
        val slot = seq and SENT_MASK
        sentSeq[slot] = seq
        sentAt[slot] = now.value
        lastSentAt = now
        return seq
    }

    /** The id of the next fragmented message. Wraps with the sequence space. */
    fun nextMessageId(): Int {
        val id = nextMessageId
        nextMessageId = (nextMessageId + 1) and PacketHeader.SEQ_MASK
        return id
    }

    /**
     * Records that [seq] arrived, and whether it should be acted on.
     *
     * @return false for a duplicate, a replay, or a sequence too old for the window to tell the
     *   two apart. A false here is a refusal, not an error: the datagram is dropped and counted.
     */
    fun onReceived(seq: Int, now: Tick): Boolean {
        if (remoteSeq == NO_SEQ) {
            remoteSeq = seq
            remoteAckBits = 0
            lastReceivedAt = now
            return true
        }
        if (PacketHeader.isNewer(seq, remoteSeq)) {
            val shift = forwardDistance(seq, remoteSeq)
            remoteAckBits = if (shift >= ACK_BITS) 0 else (remoteAckBits shl shift) or (1 shl (shift - 1))
            remoteSeq = seq
            lastReceivedAt = now
            return true
        }
        val back = forwardDistance(remoteSeq, seq)
        if (back == 0 || back > ACK_BITS) {
            // Either the newest sequence arriving twice, or one so old the window no longer
            // remembers it. Both are refused: an unbounded memory of seen sequences is exactly
            // the state a replay flood would be trying to grow.
            if (back == 0) replayDropped++ else staleDropped++
            return false
        }
        val bit = 1 shl (back - 1)
        if (remoteAckBits and bit != 0) {
            replayDropped++
            return false
        }
        remoteAckBits = remoteAckBits or bit
        lastReceivedAt = now
        return true
    }

    /**
     * Folds the peer's acknowledgement into the round-trip estimate.
     *
     * Every sequence the peer names is sampled at most once, ever: the slot is cleared on the
     * first sample, so a repeated acknowledgement of the same packet — which is the normal case,
     * since `ackBits` re-states the last 32 — cannot pull the estimate towards zero.
     */
    fun onAck(ack: Int, ackBits: Int, now: Tick) {
        sample(ack, now)
        for (index in 0 until ACK_BITS) {
            if (ackBits and (1 shl index) == 0) continue
            sample((ack - 1 - index) and PacketHeader.SEQ_MASK, now)
        }
    }

    /** Whether nothing has arrived for [timeoutTicks]. */
    fun isTimedOut(now: Tick, timeoutTicks: Long): Boolean =
        now.ticksSince(lastReceivedAt) >= timeoutTicks

    /** Whether the link has been quiet long enough to owe the peer a keep-alive. */
    fun needsKeepAlive(now: Tick, intervalTicks: Long): Boolean =
        now.ticksSince(lastSentAt) >= intervalTicks

    private fun sample(seq: Int, now: Tick) {
        val slot = seq and SENT_MASK
        if (sentSeq[slot] != seq) return
        sentSeq[slot] = NO_SEQ
        val rtt = (now.value - sentAt[slot]).toFloat()
        if (rtt < 0f) return
        if (smoothedRttTicks < 0f) {
            smoothedRttTicks = rtt
            rttVarianceTicks = rtt / 2f
            return
        }
        rttVarianceTicks = (1f - VARIANCE_GAIN) * rttVarianceTicks +
            VARIANCE_GAIN * abs(smoothedRttTicks - rtt)
        smoothedRttTicks = (1f - MEAN_GAIN) * smoothedRttTicks + MEAN_GAIN * rtt
    }

    /**
     * How far [newer] is ahead of [older] in a wrapping 16-bit space.
     *
     * Written here rather than borrowed from `ClientReplicationState`, which has the same
     * arithmetic, because `replication` depends on `transport` and the reverse import would
     * make the two packages cyclic. [PacketHeader.isNewer] is shared, since `wire` depends on
     * neither.
     */
    private fun forwardDistance(newer: Int, older: Int): Int =
        (newer - older) and PacketHeader.SEQ_MASK

    companion object {

        /** No sequence has been seen or recorded here. */
        const val NO_SEQ: Int = -1

        /** Width of the acknowledgement bitfield, and therefore of the replay window. */
        const val ACK_BITS: Int = 32

        /**
         * 256 slots of send timestamps.
         *
         * The window only has to outlive the 32 sequences an acknowledgement can name plus the
         * round trip; 256 covers a link four ticks of sends deep at any playable latency, and
         * is a power of two so the slot is a mask rather than a division.
         */
        private const val SENT_RING: Int = 256
        private const val SENT_MASK: Int = SENT_RING - 1

        private const val UNMEASURED: Float = -1f

        /** RFC 6298's `alpha`. */
        private const val MEAN_GAIN: Float = 0.125f

        /** RFC 6298's `beta`. */
        private const val VARIANCE_GAIN: Float = 0.25f

        /** RFC 6298's `K`. */
        private const val RTT_VARIANCE_WEIGHT: Float = 4f
    }
}
