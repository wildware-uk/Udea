package dev.wildware.udea.net.input

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.net.bits.Q
import dev.wildware.udea.net.bits.readQ
import dev.wildware.udea.net.bits.readVarInt
import dev.wildware.udea.net.bits.writeQ
import dev.wildware.udea.net.bits.writeVarInt
import dev.wildware.udea.net.wire.PacketHeader

/**
 * What a client is allowed to send: what the player *did*, never where the player *is*.
 *
 * ## The authority bug this closes
 *
 * `NetworkClientSystem.kt:57` is an `IntervalSystem` with no interval, so it ran EveryFrame and
 * uploaded full component state for every owned entity at render rate. Two separate failures in
 * one line. The rate is the smaller one. The larger one is *direction*: a client that pushes
 * `Transform.position` to the server owns its own position, so there is nothing to cheat past,
 * and — just as fatally — there is nothing to predict, because prediction is exactly "apply my
 * input locally, then reconcile against the server's answer". A client with no server answer to
 * reconcile against cannot predict; it can only assert.
 *
 * So the wire vocabulary is one-directional by construction (spec section 5): clients send
 * `@InputCommand` and `@ServerRpc`, and `NoClientStateUploadTest` asserts that no
 * client-to-server datagram carries a replicated component field.
 */
public data class MoveInput(

    /**
     * Monotonic per client, wrapping at 16 bits. The identity a duplicate is recognised by and
     * the anchor a reconciliation replays from.
     */
    public val seq: Int,

    /**
     * The client's simulation tick when the input was produced.
     *
     * A [Tick] and never seconds (spec section 5, Time). A command stamped in wall time cannot be
     * replayed deterministically, because replay has no wall time.
     */
    public val tick: Tick,

    /** Move axis, `-1..1`. Quantised: eight bits of a stick is more than a player can express. */
    public val moveX: Float,

    /** Move axis, `-1..1`. */
    public val moveY: Float,

    /** Aim direction in radians, `-PI..PI`. */
    public val aim: Float,

    /** Button bitfield. One bit per action; eight is the current wire width. */
    public val buttons: Int,
) {

    /** Writes this command. Fixed width apart from the two varints. */
    public fun write(out: BitWriter) {
        out.writeBits(seq and PacketHeader.SEQ_MASK, PacketHeader.SEQ_BITS)
        out.writeVarInt(tick.value.toInt())
        out.writeQ(AXIS, moveX)
        out.writeQ(AXIS, moveY)
        out.writeQ(AIM, aim)
        out.writeBits(buttons and BUTTON_MASK, BUTTON_BITS)
    }

    public companion object {

        /** Move axes: eight bits over `-1..1`. */
        public val AXIS: Q.Fixed = Q.declared(bits = 8, min = -1f, max = 1f)

        /** Aim: twelve bits over a full turn, about a twentieth of a degree. */
        public val AIM: Q.Fixed = Q.declared(bits = 12, min = -3.1416f, max = 3.1416f)

        /** Width of the button field. */
        public const val BUTTON_BITS: Int = 8
        public const val BUTTON_MASK: Int = (1 shl BUTTON_BITS) - 1

        /** Reads a command written by [write]. */
        public fun read(src: BitReader): MoveInput {
            val seq = src.readBits(PacketHeader.SEQ_BITS)
            val tick = Tick(src.readVarInt().toLong() and 0xFFFF_FFFFL)
            val moveX = src.readQ(AXIS)
            val moveY = src.readQ(AXIS)
            val aim = src.readQ(AIM)
            val buttons = src.readBits(BUTTON_BITS)
            return MoveInput(seq, tick, moveX, moveY, aim, buttons)
        }

        /**
         * The command a starved jitter buffer repeats: the last one, with movement held.
         *
         * Repeating beats freezing. A player who is running and loses one packet should keep
         * running for a tick, not stop dead and snap back when the next packet lands.
         */
        public fun repeatOf(previous: MoveInput, tick: Tick): MoveInput = previous.copy(tick = tick)
    }
}

/**
 * The client's outbound command ring: redundancy instead of retransmission.
 *
 * Every packet carries the last [redundancy] commands, so losing one datagram costs nothing at
 * all — the next one re-delivers the command that was in it. That is strictly better than
 * retransmitting: a retransmit needs an ack, a timer and an RTO estimate, and it arrives *later*
 * than the redundant copy would have, which for input is the one thing that cannot be traded.
 *
 * At 30Hz send against a 60Hz sim (spec 3.3) three copies means a command survives two
 * consecutive lost packets, which at 5% loss is a one-in-four-hundred event per command.
 */
public class InputRing(

    /** How many commands to remember. Well past [redundancy] so reconciliation can replay. */
    public val capacity: Int = DEFAULT_CAPACITY,

    /** How many recent commands each packet carries. */
    public val redundancy: Int = DEFAULT_REDUNDANCY,
) {

    init {
        require(capacity >= redundancy) { "capacity $capacity cannot hold $redundancy commands" }
        require(redundancy >= 1) { "redundancy must be at least 1, was $redundancy" }
    }

    private val commands = arrayOfNulls<MoveInput>(capacity)
    private var count = 0

    /** How many commands have ever been pushed. Also the next sequence number to mint. */
    public var produced: Long = 0L
        private set

    /** The newest command, or null before the first. */
    public val newest: MoveInput? get() = if (count == 0) null else commands[((produced - 1) % capacity).toInt()]

    /** Appends [command]. The oldest is forgotten once [capacity] is reached. */
    public fun push(command: MoveInput) {
        commands[(produced % capacity).toInt()] = command
        produced++
        if (count < capacity) count++
    }

    /**
     * Writes the last [redundancy] commands, oldest first, prefixed by how many follow.
     *
     * Oldest first so the server's jitter buffer sees them in the order it will consume them,
     * and so a reader that stops early still has a contiguous run rather than a hole.
     */
    public fun write(out: BitWriter) {
        val n = if (count < redundancy) count else redundancy
        out.writeVarInt(n)
        for (age in n - 1 downTo 0) commands[((produced - 1 - age) % capacity).toInt()]!!.write(out)
    }

    public companion object {

        /** 128 commands: over two seconds of history at 60Hz, which outlasts any plausible RTT. */
        public const val DEFAULT_CAPACITY: Int = 128

        /** Three copies of every command, per spec 3.3. */
        public const val DEFAULT_REDUNDANCY: Int = 3

        /** As many commands as one packet will decode before the length is treated as hostile. */
        public const val MAX_PER_PACKET: Int = 16
    }
}
