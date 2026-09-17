package dev.wildware.udea.replay

/**
 * Lowercase hex for the refusal messages this module writes, on every target.
 *
 * `String.format` is JVM-only (issue #206). These render what `"%02x"` and `"%0<n>x"` rendered
 * there: an `Int` is read as unsigned, so a negative value is its eight two's-complement digits
 * rather than a minus sign, and a `Byte` is its two.
 */
internal object Hex {

    /** One byte as two digits: `"%02x"`. */
    fun byte(value: Byte): String = (value.toInt() and 0xFF).toString(16).padStart(2, '0')

    /** Every byte of [bytes], two digits each and unseparated. */
    fun bytes(bytes: Iterable<Byte>): String = bytes.joinToString("") { byte(it) }

    /** [value] as unsigned, zero-padded to at least [width] digits: `"%0<width>x"`. */
    fun int(value: Int, width: Int): String = (value.toLong() and 0xFFFF_FFFFL).toString(16).padStart(width, '0')
}
