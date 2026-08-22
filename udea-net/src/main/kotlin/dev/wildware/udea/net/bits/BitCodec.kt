package dev.wildware.udea.net.bits

import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.MaskOps

/**
 * The encodings a generated `Replicator` writes, on top of the frozen
 * [BitWriter]/[BitReader] primitives.
 *
 * They are extensions rather than members of [BitBufferWriter] on purpose: the codec must
 * be expressible against the `udea-core` interface alone, so generated code depends on the
 * contract and not on this module's buffer, and so `udea-core`'s own test fixtures encode
 * identically to the real transport.
 *
 * Everything here is allocation-free and none of it needs byte alignment — [alignToByte]
 * exists for the one case that does, a payload the transport hands to a byte-oriented
 * consumer.
 */

/**
 * Pads with zero bits up to the next byte boundary. A no-op when already aligned.
 *
 * The only correct way to reach a byte boundary: `bitPosition` is authoritative and the
 * padding is part of the format, so both ends must call this at the same point.
 */
public fun BitWriter.alignToByte() {
    val pad = ((8L - (bitPosition and 7L)) and 7L).toInt()
    if (pad > 0) writeBits(0, pad)
}

/** Skips the padding written by [BitWriter.alignToByte]. */
public fun BitReader.alignToByte() {
    val pad = ((8L - (bitPosition and 7L)) and 7L).toInt()
    if (pad > 0) readBits(pad)
}

/**
 * Writes the low [fieldCount] bits of [mask].
 *
 * A field mask costs one bit per field and nothing more: a three-field component spends
 * three bits, not a byte and not 64 bits.
 *
 * This is a **delegate**, not a second encoder. Mask framing lives in `udea-core`'s
 * [MaskOps.writeTo] and nowhere else, because the mask is the one shape a generated
 * `Replicator` and this module must never disagree about — and because [FieldMask] is
 * opaque precisely so that widening it past 64 fields changes one file in `udea-core`.
 * A raw-`Long` overload here would defeat both.
 */
public fun BitWriter.writeMask(mask: FieldMask, fieldCount: Int): Unit =
    MaskOps.writeTo(mask, this, fieldCount)

/** Reads a mask written by [writeMask] with the same [fieldCount]. Delegates to [MaskOps.readFrom]. */
public fun BitReader.readMask(fieldCount: Int): FieldMask = MaskOps.readFrom(this, fieldCount)

/**
 * Writes [value] as a variable-length integer: 7 payload bits per group, high bit set on
 * every group but the last.
 *
 * [value] is treated as **unsigned**, so small non-negative values — entity counts, array
 * lengths, tick deltas — cost 8 bits and a negative one costs 40. Signed quantities use
 * [writeZigZag], which is the same encoding over a folded value.
 *
 * Groups are 8 bits but are not byte-aligned: a varint after a 3-bit field starts at bit 3.
 */
public fun BitWriter.writeVarInt(value: Int) {
    var remaining = value.toLong() and 0xFFFF_FFFFL
    while (true) {
        val group = (remaining and 0x7FL).toInt()
        remaining = remaining ushr 7
        if (remaining == 0L) {
            writeBits(group, 8)
            return
        }
        writeBits(group or 0x80, 8)
    }
}

/**
 * Reads a value written by [writeVarInt].
 *
 * @throws MalformedBitStream if the encoding continues past the five groups a 32-bit value
 *   can occupy, or decodes to more than 32 bits. A peer that sends an unterminated varint
 *   must be rejected, not allowed to walk the reader off the end of the packet.
 */
public fun BitReader.readVarInt(): Int {
    var result = 0L
    var shift = 0
    var groups = 0
    while (true) {
        val group = readBits(8)
        groups++
        result = result or ((group.toLong() and 0x7FL) shl shift)
        if (group and 0x80 == 0) break
        if (groups == 5) throw MalformedBitStream("varint continues past 5 groups")
        shift += 7
    }
    if (result > 0xFFFF_FFFFL) throw MalformedBitStream("varint decodes to more than 32 bits")
    return result.toInt()
}

/**
 * Writes [value] zig-zag folded, so that small magnitudes of either sign are short:
 * `0, -1, 1, -2` become `0, 1, 2, 3` and all cost 8 bits.
 *
 * This is what a delta-encoded quantised position uses — the whole point of a delta is that
 * it is small and signed.
 */
public fun BitWriter.writeZigZag(value: Int): Unit = writeVarInt((value shl 1) xor (value shr 31))

/** Reads a value written by [writeZigZag]. */
public fun BitReader.readZigZag(): Int {
    val folded = readVarInt()
    return (folded ushr 1) xor -(folded and 1)
}

/**
 * Writes [value] clamped into `[min, max]` and quantised to [bits] bits.
 *
 * The direct form of [Q.Fixed], for a generated `Replicator` that resolved an
 * `@Q(bits, min, max)` declaration into literals at compile time. `min` and `max` are both
 * exactly representable, so a clamped value round-trips to the bound it was clamped to.
 *
 * @throws IllegalArgumentException if [value] is NaN — a range mapping has no bit pattern
 *   that could mean it. Declare `Q.Exact` for a field that must carry NaN.
 */
public fun BitWriter.writeFixed(value: Float, min: Float, max: Float, bits: Int): Unit =
    writeBits(quantiseFixed(value, min, max, bits), bits)

/** Reads a value written by [writeFixed] with the same [min], [max] and [bits]. */
public fun BitReader.readFixed(min: Float, max: Float, bits: Int): Float =
    dequantiseFixed(readBits(bits), min, max, bits)

/** Writes [value] clamped into `0..1` in 8 bits. See [Q.Norm8]. */
public fun BitWriter.writeNorm8(value: Float): Unit =
    writeBits(quantiseFixed(value, 0f, 1f, NORM8_BITS), NORM8_BITS)

/** Reads a value written by [writeNorm8]. */
public fun BitReader.readNorm8(): Float = dequantiseFixed(readBits(NORM8_BITS), 0f, 1f, NORM8_BITS)

/**
 * Writes [value] radians wrapped into one turn, in 16 bits. See [Q.Angle16].
 *
 * Wrapping, not clamping: an angle outside `[0, 2π)` is not out of range, it is the same
 * angle written differently, and [readAngle16] returns it in `[0, 2π)`.
 *
 * @throws IllegalArgumentException if [value] is not finite.
 */
public fun BitWriter.writeAngle16(value: Float): Unit =
    writeBits(quantiseAngle16(value), ANGLE16_BITS)

/** Reads a value written by [writeAngle16]. Always in `[0, 2π)`. */
public fun BitReader.readAngle16(): Float = dequantiseAngle16(readBits(ANGLE16_BITS))

/** Writes [value] under [q], costing exactly [Q.bits] bits. */
public fun BitWriter.writeQ(q: Q, value: Float): Unit = writeBits(q.quantise(value), q.bits)

/** Reads a value written by [writeQ] under the same [q]. */
public fun BitReader.readQ(q: Q): Float = q.dequantise(readBits(q.bits))
