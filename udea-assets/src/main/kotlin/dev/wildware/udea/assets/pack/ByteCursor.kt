package dev.wildware.udea.assets.pack

/**
 * A bounds-checked little-endian read head over one section's bytes.
 *
 * Every overrun becomes a [BundleCorruptException] naming the section and the offset, rather
 * than the `ArrayIndexOutOfBoundsException` a raw `ByteArray` read would throw. That is not
 * cosmetic: a truncated bundle is a thing that happens to a *user*, mid-download or on a full
 * disk, and the difference between the two messages is the difference between "redownload the
 * game" and a stack trace in a bug report.
 */
internal class ByteCursor(
    private val bytes: ByteArray,
    private val section: String,
    private var position: Int = 0,
) {
    val offset: Int get() = position

    val remaining: Int get() = bytes.size - position

    fun u8(): Int = bytes[take(1)].toInt() and 0xFF

    fun u16(): Int = u8() or (u8() shl 8)

    fun i32(): Int {
        val at = take(4)
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }

    fun i64(): Long {
        val low = i32().toLong() and 0xFFFFFFFFL
        val high = i32().toLong() and 0xFFFFFFFFL
        return low or (high shl 32)
    }

    fun f32(): Float = Float.fromBits(i32())

    /**
     * A count written as a `u32`.
     *
     * Rejects the negative half of the range here rather than letting it become a negative
     * array size three frames further in: a corrupt count is the single most common shape of a
     * malformed binary file, and it is the one whose default failure is least readable.
     *
     * [bytesEach] is the smallest number of bytes one element can occupy **in this section**,
     * and it turns "this file claims four billion strings" into a diagnosable message instead
     * of an `OutOfMemoryError` from the allocation that follows. It is zero for a count whose
     * elements live somewhere else - the section table's count, whose entries are in a
     * different slice - because a bound taken from the wrong slice is worse than no bound: it
     * rejects files that are perfectly valid.
     */
    fun count(what: String, bytesEach: Int = 0): Int {
        val raw = i32()
        if (raw < 0) corrupt("$what count $raw (read as u32: ${raw.toLong() and 0xFFFFFFFFL})")
        if (bytesEach > 0 && raw.toLong() * bytesEach > remaining) {
            corrupt(
                "$what count $raw needs at least ${raw.toLong() * bytesEach} bytes but only " +
                    "$remaining are left in the section",
            )
        }
        return raw
    }

    fun bytes(length: Int): ByteArray {
        if (length < 0) corrupt("negative length $length")
        return bytes.copyOfRange(take(length), position)
    }

    fun utf8(length: Int): String = String(bytes(length), Charsets.UTF_8)

    fun corrupt(reason: String): Nothing =
        throw BundleCorruptException("section '$section' at byte $position: $reason")

    private fun take(length: Int): Int {
        val start = position
        if (length > bytes.size - start) {
            throw BundleCorruptException(
                "section '$section' is truncated: $length byte(s) wanted at $start, " +
                    "${bytes.size - start} left of ${bytes.size}",
            )
        }
        position = start + length
        return start
    }
}
