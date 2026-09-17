package dev.wildware.udea.net.transport

/**
 * Big-endian reads and writes at absolute offsets in a `ByteArray`.
 *
 * What `java.nio.ByteBuffer`'s absolute `get`/`put` gave the UDP layout before issue #209, in common
 * code: every datagram header field is a named offset in [UdpLayout], and a reader and a writer
 * that index the same constants through these cannot drift. Big-endian because that is what the
 * format has always been on the wire, and `ByteBuffer`'s default.
 */
internal object BigEndian {

    fun putShort(bytes: ByteArray, offset: Int, value: Short) {
        bytes[offset] = (value.toInt() ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until Int.SIZE_BYTES) {
            bytes[offset + index] = (value ushr ((Int.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
        }
    }

    fun putLong(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until Long.SIZE_BYTES) {
            bytes[offset + index] = (value ushr ((Long.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
        }
    }

    fun getShort(bytes: ByteArray, offset: Int): Short =
        ((bytes[offset].toInt() and 0xFF shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toShort()

    fun getInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (index in 0 until Int.SIZE_BYTES) value = (value shl Byte.SIZE_BITS) or (bytes[offset + index].toInt() and 0xFF)
        return value
    }

    fun getLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until Long.SIZE_BYTES) {
            value = (value shl Byte.SIZE_BITS) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }
}
