package dev.wildware.udea.net.input

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.net.bits.MalformedBitStream
import dev.wildware.udea.net.bits.readVarInt
import dev.wildware.udea.net.wire.PacketHeader

/**
 * The server's per-client input queue: absorbs jitter, consumes exactly one command per sim tick.
 *
 * ## Why a buffer and not "apply whatever arrived"
 *
 * Clients send input at 30Hz and the simulation runs at 60Hz (spec 3.3), so even on a perfect
 * link the arrival pattern does not match the consumption pattern. Add jitter and packets arrive
 * in bursts of three and then not at all. Consuming directly from arrivals makes the simulation's
 * input rate a function of the network, which is a non-deterministic simulation. Holding
 * [targetDepth] commands and consuming exactly one per tick makes it a function of the tick rate,
 * which is what a deterministic rollback needs.
 *
 * Deeper is smoother and laggier; the depth is the whole trade and it is therefore a parameter,
 * not a constant buried in a loop.
 *
 * ## Idempotence
 *
 * Every packet carries the last three commands ([InputRing]), so the *normal* case is that two
 * thirds of what arrives has already been seen. [offer] drops a command that is already queued
 * and a command whose tick has already been simulated, and inserts everything else **in sequence
 * order** rather than arrival order.
 *
 * The distinction matters and it is where a naive implementation loses input: "older than the
 * newest command I have seen" is not the same as "already simulated". A packet that overtakes its
 * predecessor makes the predecessor look old while its input has not been applied yet, and
 * rejecting it silently drops a real command — the player's stutter that nothing in the logs
 * explains. Only [lastProcessedInputSeq] can decide that, and it is what this uses.
 *
 * `NetworkClientSystem.kt:75` handled the inbound command packet with a literal `TODO()`.
 */
public class JitterBuffer(

    /** How many commands to hold before consuming. Two to three at 60Hz (spec 3.3). */
    public val targetDepth: Int = DEFAULT_TARGET_DEPTH,

    /** Ring capacity. Anything beyond this is a client flooding, and the oldest is dropped. */
    public val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(targetDepth >= 1) { "targetDepth must be at least 1, was $targetDepth" }
        require(capacity > targetDepth) { "capacity $capacity cannot buffer $targetDepth commands" }
    }

    private val queue = ArrayDeque<MoveInput>(capacity)

    /** The last command consumed, repeated on starvation. */
    public var lastConsumed: MoveInput? = null
        private set

    /** The highest sequence ever accepted, for duplicate and reorder rejection. */
    public var highestAccepted: Int = NO_SEQ
        private set

    /** The sequence of the last command actually consumed. Rides the owner's snapshot section. */
    public var lastProcessedInputSeq: Int = NO_SEQ
        private set

    /** Commands accepted into the queue. */
    public var accepted: Long = 0L
        private set

    /** Commands dropped as already seen. High and healthy: redundancy is doing its job. */
    public var duplicates: Long = 0L
        private set

    /** Commands dropped because the tick they belong to has already been simulated. */
    public var stale: Long = 0L
        private set

    /** Ticks that consumed a repeat because the queue was empty. Surfaced by `net.desync_report`. */
    public var starvations: Long = 0L
        private set

    /** Commands discarded because the client sent faster than the server consumes. */
    public var overflows: Long = 0L
        private set

    /** How many commands are waiting. */
    public val depth: Int get() = queue.size

    /**
     * Offers [command].
     *
     * @return true when it was queued, false when it was a duplicate, stale, or dropped for
     *   overflow. All three are ordinary events on a real link, not errors.
     */
    public fun offer(command: MoveInput): Boolean {
        // Already simulated. Re-queueing it would replay a tick of input the world has moved past.
        if (lastProcessedInputSeq != NO_SEQ && !PacketHeader.isNewer(command.seq, lastProcessedInputSeq)) {
            stale++
            return false
        }
        // Already queued: the redundancy doing its job, which is the common case rather than an
        // error — two thirds of everything that arrives is a copy of something that already did.
        for (queued in queue) if (queued.seq == command.seq) {
            duplicates++
            return false
        }
        if (queue.size == capacity) {
            queue.removeFirst()
            overflows++
        }
        insertBySequence(command)
        if (highestAccepted == NO_SEQ || PacketHeader.isNewer(command.seq, highestAccepted)) {
            highestAccepted = command.seq
        }
        accepted++
        return true
    }

    /**
     * Inserts [command] so the queue stays ascending by sequence.
     *
     * Appending instead would let arrival order decide simulation order, which is the whole thing
     * a jitter buffer exists to prevent: a command that overtook its predecessor on the wire would
     * be simulated first, and the two clients would then disagree about what the player did. The
     * scan walks from the back, so the ordinary in-order arrival costs one comparison.
     */
    private fun insertBySequence(command: MoveInput) {
        var position = queue.size
        while (position > 0 && PacketHeader.isNewer(queue[position - 1].seq, command.seq)) position--
        if (position == queue.size) queue.addLast(command) else queue.add(position, command)
    }

    /**
     * Consumes exactly one command for [tick].
     *
     * Holds back until [targetDepth] commands are queued, which is the buffering. Once flowing,
     * an empty queue repeats [lastConsumed] with [tick] restamped and increments [starvations] —
     * a running player keeps running for a tick rather than stopping dead and snapping back.
     *
     * @return the command to simulate, or null before the very first one has arrived.
     */
    public fun consume(tick: Tick): MoveInput? {
        if (queue.size >= targetDepth || (queue.isNotEmpty() && lastConsumed != null)) {
            val command = queue.removeFirst()
            lastConsumed = command
            lastProcessedInputSeq = command.seq
            return command
        }
        val previous = lastConsumed ?: return null
        starvations++
        val repeat = MoveInput.repeatOf(previous, tick)
        lastConsumed = repeat
        return repeat
    }

    /**
     * Reads a command block written by [InputRing.write] and offers every command in it.
     *
     * @return how many were newly accepted.
     */
    public fun readInto(src: BitReader): Int {
        val count = src.readVarInt()
        if (count !in 0..InputRing.MAX_PER_PACKET) {
            throw MalformedBitStream(
                "input block declares $count commands, over the ${InputRing.MAX_PER_PACKET} limit",
            )
        }
        var acceptedHere = 0
        repeat(count) { if (offer(MoveInput.read(src))) acceptedHere++ }
        return acceptedHere
    }

    public companion object {

        /** No sequence has been seen yet. Not a valid sequence number. */
        public const val NO_SEQ: Int = -1

        /** Two commands of slack: about 33ms at 60Hz (spec 3.3). */
        public const val DEFAULT_TARGET_DEPTH: Int = 2

        /** A third of a second of input at 60Hz. Past that the client is flooding. */
        public const val DEFAULT_CAPACITY: Int = 20
    }
}
