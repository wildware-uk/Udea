package dev.wildware.udea.core.fixtures

import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter

/**
 * A growable little-endian bit buffer.
 *
 * The real `BitWriter`/`BitReader` — framing, pooled buffers, `@Q` quantisation, the packet
 * header — belong to `udea-net`. This pair exists so the `Replicator` contract can be driven
 * end to end (capture, diff, write, read, apply) without the networking module, and so the
 * "an empty mask emits zero bits" claim can actually be measured rather than asserted.
 */
public class ArrayBitWriter(initialWords: Int = 8) : BitWriter {

    private var words = LongArray(if (initialWords > 0) initialWords else 1)
    private var written = 0L

    override val bitPosition: Long get() = written

    override fun writeBits(value: Int, bitCount: Int) {
        require(bitCount in 1..32) { "bitCount must be in 1..32, was $bitCount" }
        put(value.toLong() and lowMask(bitCount), bitCount)
    }

    override fun writeBoolean(value: Boolean): Unit = put(if (value) 1L else 0L, 1)

    override fun writeInt(value: Int): Unit = put(value.toLong() and 0xFFFF_FFFFL, 32)

    override fun writeLong(value: Long): Unit = put(value, 64)

    override fun writeFloat(value: Float): Unit = writeInt(value.toRawBits())

    /** A reader positioned at bit zero over everything written so far. */
    public fun toReader(): ArrayBitReader = ArrayBitReader(words.copyOf(), written)

    private fun put(value: Long, bitCount: Int) {
        var remaining = bitCount
        var source = value
        while (remaining > 0) {
            val wordIndex = (written ushr 6).toInt()
            grow(wordIndex)
            val offset = (written and 63L).toInt()
            val take = minOf(64 - offset, remaining)
            words[wordIndex] = words[wordIndex] or ((source and lowMask(take)) shl offset)
            written += take
            source = if (take == 64) 0L else source ushr take
            remaining -= take
        }
    }

    private fun grow(wordIndex: Int) {
        if (wordIndex < words.size) return
        var size = words.size
        while (size <= wordIndex) size *= 2
        words = words.copyOf(size)
    }
}

/** The read side of [ArrayBitWriter]. */
public class ArrayBitReader(
    private val words: LongArray,
    private val bitLength: Long,
) : BitReader {

    private var read = 0L

    override val bitPosition: Long get() = read

    /** Bits written but not yet consumed. */
    public val remaining: Long get() = bitLength - read

    override fun readBits(bitCount: Int): Int {
        require(bitCount in 1..32) { "bitCount must be in 1..32, was $bitCount" }
        return take(bitCount).toInt()
    }

    override fun readBoolean(): Boolean = take(1) != 0L

    override fun readInt(): Int = take(32).toInt()

    override fun readLong(): Long = take(64)

    override fun readFloat(): Float = Float.fromBits(readInt())

    private fun take(bitCount: Int): Long {
        check(read + bitCount <= bitLength) {
            "read past end of buffer: needed $bitCount bits at $read, buffer holds $bitLength"
        }
        var remainingBits = bitCount
        var shift = 0
        var result = 0L
        while (remainingBits > 0) {
            val wordIndex = (read ushr 6).toInt()
            val offset = (read and 63L).toInt()
            val take = minOf(64 - offset, remainingBits)
            val chunk = (words[wordIndex] ushr offset) and lowMask(take)
            result = result or (chunk shl shift)
            read += take
            shift += take
            remainingBits -= take
        }
        return result
    }
}

private fun lowMask(bitCount: Int): Long =
    if (bitCount >= 64) -1L else (1L shl bitCount) - 1L
