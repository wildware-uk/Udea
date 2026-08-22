package dev.wildware.udea.net.bits

import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter

/**
 * Anything the bit stream refuses to do. Sealed so a framing layer can exhaustively decide
 * between "my buffer was too small" (retry with a bigger datagram) and "the peer sent
 * nonsense" (drop the packet and disconnect the peer).
 */
public sealed class BitBufferException(message: String) : RuntimeException(message)

/**
 * A write did not fit in the caller-supplied buffer.
 *
 * Typed rather than `IndexOutOfBoundsException` because this is a routine, recoverable
 * event on a packet boundary: the framing layer's answer is "flush this datagram and start
 * a new one", and it needs [requestedBits] and [bitPosition] to decide. The buffer is left
 * exactly as it was before the failing call, so everything already written is still valid
 * and still sendable.
 *
 * [field] is whatever [BitBufferWriter.currentField] held, so a generated `Replicator`
 * that sets it names the offending component field in the message instead of leaving a bit
 * offset to be decoded by hand.
 */
public class BitBufferOverflow internal constructor(
    public val field: String?,
    public val requestedBits: Int,
    public val bitPosition: Long,
    public val capacityBits: Long,
) : BitBufferException(
    buildString {
        append("bit buffer overflow")
        if (field != null) append(" writing field '").append(field).append('\'')
        append(": needed ").append(requestedBits)
        append(" bit(s) at bit ").append(bitPosition)
        append(", capacity is ").append(capacityBits)
        append(" bit(s) (").append(capacityBits / 8).append(" byte(s))")
    },
)

/**
 * A read ran off the end of the readable range.
 *
 * Loud on purpose: the alternative is returning zeroes for the tail of a truncated packet,
 * which decodes into a plausible-looking component and desyncs the client silently.
 */
public class BitBufferUnderflow internal constructor(
    public val requestedBits: Int,
    public val bitPosition: Long,
    public val limitBits: Long,
) : BitBufferException(
    "bit buffer underflow: needed $requestedBits bit(s) at bit $bitPosition, " +
        "readable range holds $limitBits bit(s)",
)

/** The bits present are not a legal encoding — a hostile or corrupted peer, not a short read. */
public class MalformedBitStream internal constructor(message: String) : BitBufferException(message)

/**
 * A [BitWriter] over a caller-supplied [ByteArray] slice.
 *
 * Three properties, all of which the old `common/network/packets.kt` lacked:
 *
 * - **Bit-level.** A 3-field mask costs 3 bits and a boolean costs 1. The old path spent a
 *   whole byte on each, then sent 2048 bytes whatever it used.
 * - **Caller-supplied, never growing.** The buffer belongs to the transport, is sized to
 *   the MTU, and is reused every tick via [reset]. There is no internal allocation at all
 *   after construction, so a full tick of writes allocates nothing.
 * - **Loud on exhaustion.** Running out of room throws [BitBufferOverflow] *before*
 *   touching the buffer, so the bytes written so far remain a valid, sendable prefix.
 *
 * Bit order is least-significant-bit first within each byte, bytes ascending. [byteLength]
 * is the exact number of bytes to hand to the transport — the length prefix the old
 * `EntityUpdate` never had, and the reason two messages can now share one datagram.
 *
 * Not thread safe, and deliberately so: one writer per send thread, reused.
 */
public class BitBufferWriter(
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset,
) : BitWriter {

    private var buffer: ByteArray
    private var offset: Int = 0
    private var capacityBytes: Int = 0
    private var written: Long = 0L

    init {
        checkRange(buffer.size, offset, length)
        this.buffer = buffer
        this.offset = offset
        this.capacityBytes = length
    }

    /**
     * The field currently being written, used only to name the culprit in a
     * [BitBufferOverflow]. Assigning a compile-time constant costs nothing, so a generated
     * `Replicator` can set it per field without giving up the allocation-free property.
     */
    public var currentField: String? = null

    /** Bits written since the last [reset]. Exact across byte boundaries. */
    public val bitsWritten: Long get() = written

    override val bitPosition: Long get() = written

    /** How many bits the bound slice can hold in total. */
    public val capacityBits: Long get() = capacityBytes.toLong() shl 3

    /** How many more bits fit before the next write overflows. */
    public val remainingBits: Long get() = capacityBits - written

    /**
     * Bytes that must be sent for everything written so far to be readable — [bitsWritten]
     * rounded up. The trailing bits of the last byte are zero-padded.
     */
    public val byteLength: Int get() = ((written + 7L) ushr 3).toInt()

    /** Rewinds to bit zero over the same slice. Subsequent writes overwrite, never OR. */
    public fun reset() {
        written = 0L
        currentField = null
    }

    /** Rebinds to a different slice and rewinds. Allocates nothing. */
    public fun reset(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        checkRange(buffer.size, offset, length)
        this.buffer = buffer
        this.offset = offset
        this.capacityBytes = length
        reset()
    }

    override fun writeBits(value: Int, bitCount: Int) {
        require(bitCount in 1..32) { "bitCount must be in 1..32, was $bitCount" }
        put(value.toLong() and lowMask(bitCount), bitCount)
    }

    override fun writeBoolean(value: Boolean): Unit = put(if (value) 1L else 0L, 1)

    override fun writeInt(value: Int): Unit = put(value.toLong() and 0xFFFF_FFFFL, 32)

    override fun writeLong(value: Long): Unit = put(value, 64)

    /** Writes all 32 bits of the IEEE-754 encoding. NaN and infinities survive unchanged. */
    override fun writeFloat(value: Float): Unit = writeInt(value.toRawBits())

    /**
     * A reader over exactly the bytes written so far.
     *
     * Allocates, so this is a test and tooling convenience — the transport constructs one
     * reader and [BitBufferReader.reset]s it per received datagram.
     */
    public fun toReader(): BitBufferReader = BitBufferReader(buffer, offset, byteLength)

    private fun put(value: Long, bitCount: Int) {
        if (written + bitCount > capacityBits) {
            throw BitBufferOverflow(currentField, bitCount, written, capacityBits)
        }
        var remaining = bitCount
        var source = value
        var position = written
        while (remaining > 0) {
            val byteIndex = offset + (position ushr 3).toInt()
            val bitOffset = (position and 7L).toInt()
            val take = if (8 - bitOffset < remaining) 8 - bitOffset else remaining
            val chunk = (source and lowMask(take)).toInt()
            // A byte we are starting is overwritten outright, so stale bytes in a recycled
            // buffer can never leak into the payload; a byte we are continuing keeps only
            // the bits below bitOffset.
            val keep = if (bitOffset == 0) 0 else buffer[byteIndex].toInt() and ((1 shl bitOffset) - 1)
            buffer[byteIndex] = (keep or (chunk shl bitOffset)).toByte()
            position += take
            source = if (take >= 64) 0L else source ushr take
            remaining -= take
        }
        written = position
    }
}

/**
 * The read side of [BitBufferWriter], over a caller-supplied [ByteArray] slice.
 *
 * The readable range is the `length` bytes given to the constructor or to [reset] — the
 * framing layer knows how long each message is and hands over exactly that. Reading past
 * it throws [BitBufferUnderflow] rather than yielding zeroes.
 */
public class BitBufferReader(
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset,
) : BitReader {

    private var buffer: ByteArray
    private var offset: Int = 0
    private var limit: Long = 0L
    private var read: Long = 0L

    init {
        checkRange(buffer.size, offset, length)
        this.buffer = buffer
        this.offset = offset
        this.limit = length.toLong() shl 3
    }

    /** Bits consumed since the last [reset]. Exact across byte boundaries. */
    public val bitsRead: Long get() = read

    override val bitPosition: Long get() = read

    /** Bits left in the readable range. */
    public val remainingBits: Long get() = limit - read

    /** Rewinds to bit zero over the same slice. */
    public fun reset() {
        read = 0L
    }

    /** Rebinds to a different slice and rewinds. Allocates nothing. */
    public fun reset(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        checkRange(buffer.size, offset, length)
        this.buffer = buffer
        this.offset = offset
        this.limit = length.toLong() shl 3
        read = 0L
    }

    override fun readBits(bitCount: Int): Int {
        require(bitCount in 1..32) { "bitCount must be in 1..32, was $bitCount" }
        return take(bitCount).toInt()
    }

    override fun readBoolean(): Boolean = take(1) != 0L

    override fun readInt(): Int = take(32).toInt()

    override fun readLong(): Long = take(64)

    override fun readFloat(): Float = Float.fromBits(readInt())

    private fun take(bitCount: Int): Long {
        if (read + bitCount > limit) throw BitBufferUnderflow(bitCount, read, limit)
        var remaining = bitCount
        var shift = 0
        var result = 0L
        var position = read
        while (remaining > 0) {
            val byteIndex = offset + (position ushr 3).toInt()
            val bitOffset = (position and 7L).toInt()
            val take = if (8 - bitOffset < remaining) 8 - bitOffset else remaining
            val chunk = ((buffer[byteIndex].toInt() ushr bitOffset).toLong()) and lowMask(take)
            result = result or (chunk shl shift)
            position += take
            shift += take
            remaining -= take
        }
        read = position
        return result
    }
}

internal fun lowMask(bitCount: Int): Long =
    if (bitCount >= 64) -1L else (1L shl bitCount) - 1L

private fun checkRange(size: Int, offset: Int, length: Int) {
    require(offset >= 0) { "offset must be >= 0, was $offset" }
    require(length >= 0) { "length must be >= 0, was $length" }
    require(offset + length <= size) {
        "slice [$offset, ${offset + length}) does not fit a $size byte buffer"
    }
}
