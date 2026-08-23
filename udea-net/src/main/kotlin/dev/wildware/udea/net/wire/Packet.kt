package dev.wildware.udea.net.wire

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.bits.MalformedBitStream
import dev.wildware.udea.net.bits.alignToByte
import dev.wildware.udea.net.bits.readVarInt
import dev.wildware.udea.net.bits.writeVarInt

/**
 * The fixed head of every datagram (spec section 5, issue #106).
 *
 * ```
 *  u16 protoHash | u16 seq | u16 ack | u32 ackBits | u8 flags | varint serverTick | varint baselineTick
 * ```
 *
 * Everything the transport-agnostic layers need to do their job rides here and nowhere else:
 * [protoHash] is the continuous "are we still the same build" check, [seq]/[ack]/[ackBits] are
 * one shared ack view for both directions (which two independent KryoNet sockets structurally
 * cannot provide), and [serverTick]/[baselineTick] name exactly which pair of snapshot-ring
 * slots the payload was delta-encoded between.
 *
 * `baselineTick` is present in the bytes even when [hasBaseline] is false — a fixed header
 * shape costs one byte in the full-state case and buys a parser with no branch in it.
 */
public data class PacketHeader(

    /** [ProtocolDescriptor.protoHash] of the sender. */
    public val protoHash: Int,

    /** This packet's sequence number, wrapping at 16 bits. */
    public val seq: Int,

    /** Highest sequence the sender has received from the receiver. */
    public val ack: Int,

    /** Bit `n` set means the sender also received `ack - 1 - n`. */
    public val ackBits: Int,

    /** Server tick this packet describes. */
    public val serverTick: Tick,

    /** Tick this packet was delta-encoded against. Meaningless unless [hasBaseline]. */
    public val baselineTick: Tick,

    /** False for a full-state packet: the payload stands alone. */
    public val hasBaseline: Boolean,
) {

    /** Writes the header. Always the same fields in the same order. */
    public fun write(out: BitWriter) {
        out.writeBits(protoHash, ProtocolDescriptor.PROTO_HASH_BITS)
        out.writeBits(seq and SEQ_MASK, SEQ_BITS)
        out.writeBits(ack and SEQ_MASK, SEQ_BITS)
        out.writeInt(ackBits)
        out.writeBits(if (hasBaseline) FLAG_HAS_BASELINE else 0, FLAG_BITS)
        out.writeVarInt(serverTick.value.toInt())
        out.writeVarInt(if (hasBaseline) baselineTick.value.toInt() else 0)
    }

    public companion object {

        /** Sequence numbers wrap at 16 bits: `seq`, `ack` and every baseline key. */
        public const val SEQ_BITS: Int = 16
        public const val SEQ_MASK: Int = (1 shl SEQ_BITS) - 1

        /** Width of the flags byte. Only bit 0 is assigned. */
        public const val FLAG_BITS: Int = 8

        /** Bit 0: the payload is a delta against [baselineTick]. */
        public const val FLAG_HAS_BASELINE: Int = 1

        /** Reads a header written by [write]. */
        public fun read(src: BitReader): PacketHeader {
            val protoHash = src.readBits(ProtocolDescriptor.PROTO_HASH_BITS)
            val seq = src.readBits(SEQ_BITS)
            val ack = src.readBits(SEQ_BITS)
            val ackBits = src.readInt()
            val flags = src.readBits(FLAG_BITS)
            val serverTick = Tick(src.readVarInt().toLong() and 0xFFFF_FFFFL)
            val baselineTick = Tick(src.readVarInt().toLong() and 0xFFFF_FFFFL)
            val hasBaseline = flags and FLAG_HAS_BASELINE != 0
            if (hasBaseline && baselineTick >= serverTick) {
                throw MalformedBitStream(
                    "packet claims a baseline at $baselineTick for server tick $serverTick; " +
                        "a baseline must be strictly older than the tick it is a baseline for",
                )
            }
            return PacketHeader(protoHash, seq, ack, ackBits, serverTick, baselineTick, hasBaseline)
        }

        /**
         * Whether [a] is newer than [b] under 16-bit wraparound.
         *
         * The standard sequence comparison: `65535` is older than `0`, and a difference of more
         * than half the space is read as a wrap. A naive `a > b` stalls acknowledgement forever
         * the first time the counter wraps, roughly eighteen minutes into a 60Hz session.
         */
        public fun isNewer(a: Int, b: Int): Boolean {
            val x = a and SEQ_MASK
            val y = b and SEQ_MASK
            val half = 1 shl (SEQ_BITS - 1)
            return (x > y && x - y <= half) || (y > x && y - x > half)
        }
    }
}

/** What a framed message inside a datagram carries. */
public enum class MessageType(public val id: Int) {

    /** Server to client: a delta snapshot section. */
    Snapshot(1),

    /** Client to server: input commands. Never component state. */
    Input(2),

    /** Either direction, at connect: a [ProtocolDescriptor]. */
    ProtocolAdvert(3),

    /** Server to client: the connection is refused, with the differences spelled out. */
    ProtocolRefusal(4),
    ;

    public companion object {

        private val byId: Map<Int, MessageType> = entries.associateBy(MessageType::id)

        /** @throws MalformedBitStream for an id this build does not know. */
        public fun of(id: Int): MessageType = byId[id]
            ?: throw MalformedBitStream("unknown message type id $id")
    }
}

/**
 * Packs several length-prefixed messages into one datagram.
 *
 * ## The defect this makes impossible
 *
 * `packets.kt:66` allocated a fixed 2048-byte buffer per packet and `:117` handed **all** of it
 * to the transport with no length prefix anywhere: the receiver could not tell payload from
 * padding, one datagram could carry exactly one message, and the whole thing exceeded a
 * 1500-byte MTU by 548 bytes on every send.
 *
 * Here each message is `u8 type | u16 byteLength | payload` and the payload is byte-aligned, so
 * a reader bound to one frame cannot walk into the next however malformed the payload is, and a
 * snapshot that runs out of room is truncated at an entity boundary rather than at a random bit.
 */
public class FrameWriter(

    /** The datagram buffer. Owned by the caller and reused every tick. */
    private val writer: BitBufferWriter,
) {

    private var openType: MessageType? = null
    private var openLengthBitPosition = 0L
    private var openPayloadStart = 0L

    /** Bytes written so far, which is what to hand [dev.wildware.udea.net.transport.Transport.send]. */
    public val byteLength: Int get() = writer.byteLength

    /**
     * Begins a message of [type]. The length is back-patched by [endMessage].
     *
     * @throws IllegalStateException if a message is already open. Nesting frames would produce
     *   a length prefix covering another length prefix, which no reader can unpick.
     */
    public fun beginMessage(type: MessageType): BitWriter {
        check(openType == null) { "message of type $openType is still open" }
        writer.alignToByte()
        writer.writeBits(type.id, TYPE_BITS)
        openLengthBitPosition = writer.bitPosition
        writer.writeBits(0, LENGTH_BITS)
        openPayloadStart = writer.bitPosition
        openType = type
        return writer
    }

    /**
     * Closes the open message and patches its length.
     *
     * @return the payload length in bytes.
     */
    public fun endMessage(): Int {
        val type = checkNotNull(openType) { "no message is open" }
        writer.alignToByte()
        val payloadBits = writer.bitPosition - openPayloadStart
        val payloadBytes = (payloadBits ushr 3).toInt()
        check(payloadBytes <= MAX_PAYLOAD_BYTES) {
            "a $type message of $payloadBytes bytes exceeds the $MAX_PAYLOAD_BYTES byte frame limit"
        }
        writer.patchBits(openLengthBitPosition, payloadBytes, LENGTH_BITS)
        openType = null
        return payloadBytes
    }

    public companion object {

        /** Width of the message type tag. */
        public const val TYPE_BITS: Int = 8

        /** Width of the message length prefix, in bits. */
        public const val LENGTH_BITS: Int = 16

        /** Largest payload a [LENGTH_BITS]-wide prefix can describe. */
        public const val MAX_PAYLOAD_BYTES: Int = (1 shl LENGTH_BITS) - 1

        /** Bytes a frame costs before its payload. */
        public const val FRAME_OVERHEAD_BYTES: Int = (TYPE_BITS + LENGTH_BITS) / 8
    }
}

/** One message found in a datagram: its type and the exact slice holding its payload. */
public data class Frame(
    public val type: MessageType,
    public val offset: Int,
    public val length: Int,
)

/**
 * Walks the frames of a datagram after its [PacketHeader].
 *
 * Each frame's payload is byte-aligned and length-prefixed, so a reader can be built over
 * exactly that slice and cannot walk into the next message however malformed the payload is.
 * That containment is the property `PacketUtil`'s tag-free component stream lacked.
 */
public class FrameReader(
    private val buffer: ByteArray,
    private val offset: Int,
    private val length: Int,
    headerBits: Long,
) {

    private var cursor: Int = offset + ((headerBits + 7L) ushr 3).toInt()

    /** The next frame, or null at the end of the datagram. */
    public fun next(): Frame? {
        val end = offset + length
        if (cursor >= end) return null
        if (cursor + FrameWriter.FRAME_OVERHEAD_BYTES > end) {
            throw MalformedBitStream(
                "datagram ends mid-frame-header: ${end - cursor} byte(s) left, " +
                    "${FrameWriter.FRAME_OVERHEAD_BYTES} needed",
            )
        }
        val type = MessageType.of(buffer[cursor].toInt() and 0xFF)
        val payloadLength = (buffer[cursor + 1].toInt() and 0xFF) or
            ((buffer[cursor + 2].toInt() and 0xFF) shl 8)
        val payloadStart = cursor + FrameWriter.FRAME_OVERHEAD_BYTES
        if (payloadStart + payloadLength > end) {
            throw MalformedBitStream(
                "frame of type $type claims $payloadLength payload byte(s) but only " +
                    "${end - payloadStart} remain",
            )
        }
        cursor = payloadStart + payloadLength
        return Frame(type, payloadStart, payloadLength)
    }

    /** A reader bound to exactly [frame]'s payload. */
    public fun readerFor(frame: Frame): BitBufferReader = BitBufferReader(buffer, frame.offset, frame.length)

    /** Binds [reader] to [frame]'s payload without allocating. */
    public fun readerFor(frame: Frame, reader: BitBufferReader): BitBufferReader {
        reader.reset(buffer, frame.offset, frame.length)
        return reader
    }
}
