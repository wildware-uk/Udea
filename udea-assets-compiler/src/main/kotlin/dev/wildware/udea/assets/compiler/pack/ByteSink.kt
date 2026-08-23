package dev.wildware.udea.assets.compiler.pack

import java.io.ByteArrayOutputStream

/**
 * A growable little-endian byte buffer.
 *
 * Every write is fixed-width. There is no varint, no "omit the field when it is the default",
 * and no alignment padding, because each of those is a place where two encoders of the same
 * value could disagree, and the whole acceptance criterion for this file is that they cannot.
 */
internal class ByteSink(initialCapacity: Int = 1 shl 12) {

    private val bytes = ByteArrayOutputStream(initialCapacity)

    val size: Int get() = bytes.size()

    fun u8(value: Int) {
        require(value in 0..0xFF) { "$value does not fit in a byte" }
        bytes.write(value)
    }

    fun bool(value: Boolean): Unit = u8(if (value) 1 else 0)

    fun u16(value: Int) {
        require(value in 0..0xFFFF) { "$value does not fit in two bytes" }
        u8(value and 0xFF)
        u8((value ushr 8) and 0xFF)
    }

    fun i32(value: Int) {
        bytes.write(value and 0xFF)
        bytes.write((value ushr 8) and 0xFF)
        bytes.write((value ushr 16) and 0xFF)
        bytes.write((value ushr 24) and 0xFF)
    }

    fun i64(value: Long) {
        i32(value.toInt())
        i32((value ushr 32).toInt())
    }

    fun f32(value: Float): Unit = i32(value.toRawBits())

    fun raw(value: ByteArray): Unit = bytes.write(value, 0, value.size)

    /** A length-prefixed UTF-8 string. Used only by the string tables. */
    fun string(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        i32(encoded.size)
        raw(encoded)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

/**
 * The string table shared by a section's values.
 *
 * Sorted and deduplicated, so the table is a function of the *set* of strings a section holds
 * and not of the order they were met in. That matters more than it sounds: without the sort,
 * adding one asset would renumber every string after it and change every byte of the section,
 * which would make "is this bundle the same as the last one" useless as a hot-reload signal.
 */
internal class StringTable(strings: Iterable<String>) {

    /** Every distinct string, in natural (UTF-16 code unit) order. */
    val entries: List<String> = strings.distinct().sorted()

    private val index: Map<String, Int> = entries.withIndex().associate { (at, value) -> value to at }

    val size: Int get() = entries.size

    operator fun get(value: String): Int = index[value]
        ?: error("'$value' was not collected into the string table before the section was written")

    fun writeTo(sink: ByteSink) {
        sink.i32(entries.size)
        entries.forEach(sink::string)
    }
}
