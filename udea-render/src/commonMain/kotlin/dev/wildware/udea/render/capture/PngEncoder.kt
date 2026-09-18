package dev.wildware.udea.render.capture

/**
 * RGBA8888 pixels to PNG bytes, in common code.
 *
 * ## Why this is written here
 *
 * Spec section 4: a capture is "PNG encoded by a common-code encoder". LibGDX's `PixmapIO` and the
 * JDK's `ImageIO` and `Deflater` exist on the JVM only, and a capture has to be encodable on every
 * target the renderer runs on. A PNG writer is small; the one real piece of work is DEFLATE, and
 * this carries a compact one: LZ77 over a hash chain, coded with DEFLATE's fixed Huffman table in a
 * single block. A game frame is mostly long runs of identical pixels, which is exactly what a
 * back-reference four bytes behind collapses, so the fixed table loses little against a dynamic
 * one at a fraction of the code.
 *
 * ## What it always writes
 *
 * Colour type 6 (8-bit RGBA), no interlace, filter type 0 on every row. Rows are written in the
 * order given, which callers pass top row first.
 */
internal object PngEncoder {

    /**
     * Encodes [rgba] - `width * height * 4` bytes, top row first - as a PNG.
     */
    fun encode(width: Int, height: Int, rgba: ByteArray): ByteArray {
        require(width > 0 && height > 0) { "a PNG must have positive extent, was ${width}x$height" }
        require(rgba.size == width * height * BYTES_PER_PIXEL) {
            "${width}x$height RGBA is ${width * height * BYTES_PER_PIXEL} bytes, but ${rgba.size} were given"
        }

        val rowBytes = width * BYTES_PER_PIXEL
        val scanlines = ByteArray(height * (rowBytes + 1))
        for (row in 0 until height) {
            val to = row * (rowBytes + 1)
            scanlines[to] = FILTER_NONE
            rgba.copyInto(scanlines, to + 1, row * rowBytes, (row + 1) * rowBytes)
        }

        val out = ByteSink(scanlines.size / ESTIMATED_RATIO + HEADER_ALLOWANCE)
        out.write(SIGNATURE)

        val header = ByteSink(IHDR_LENGTH)
        header.writeInt(width)
        header.writeInt(height)
        header.writeByte(BIT_DEPTH)
        header.writeByte(COLOUR_TYPE_RGBA)
        header.writeByte(0) // compression: deflate
        header.writeByte(0) // filter method: adaptive, every row type 0 here
        header.writeByte(0) // no interlace
        chunk(out, "IHDR", header.toByteArray())
        chunk(out, "IDAT", zlib(scanlines))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** DEFLATE [data] inside a zlib wrapper, as a PNG's IDAT carries it. */
    internal fun zlib(data: ByteArray): ByteArray {
        val bits = BitSink(data.size / ESTIMATED_RATIO + HEADER_ALLOWANCE)
        // CMF: deflate with a 32K window. FLG: no dictionary, fastest level, check bits so that
        // (CMF * 256 + FLG) is a multiple of 31.
        bits.writeAlignedByte(ZLIB_CMF)
        bits.writeAlignedByte(ZLIB_FLG)
        deflateFixed(data, bits)
        bits.flushToByte()
        val adler = adler32(data)
        bits.writeAlignedByte(adler ushr 24)
        bits.writeAlignedByte(adler ushr 16)
        bits.writeAlignedByte(adler ushr 8)
        bits.writeAlignedByte(adler)
        return bits.toByteArray()
    }

    private fun deflateFixed(data: ByteArray, bits: BitSink) {
        bits.writeBits(1, 1) // BFINAL: the only block
        bits.writeBits(1, 2) // BTYPE 01: fixed Huffman

        val head = IntArray(HASH_SIZE) { -1 }
        val previous = IntArray(WINDOW_SIZE)
        var position = 0
        while (position < data.size) {
            var bestLength = 0
            var bestDistance = 0
            if (position + MIN_MATCH <= data.size) {
                val hash = hash(data, position)
                var candidate = head[hash]
                var chain = 0
                val limit = minOf(MAX_MATCH, data.size - position)
                while (candidate >= 0 && position - candidate <= WINDOW_SIZE && chain < MAX_CHAIN) {
                    var length = 0
                    while (length < limit && data[candidate + length] == data[position + length]) length++
                    if (length > bestLength) {
                        bestLength = length
                        bestDistance = position - candidate
                        if (length == limit) break
                    }
                    candidate = previous[candidate and WINDOW_MASK]
                    chain++
                }
            }

            if (bestLength >= MIN_MATCH) {
                writeLength(bits, bestLength)
                writeDistance(bits, bestDistance)
                // Every position the match covers joins the chain, so a later match can start inside it.
                val end = position + bestLength
                while (position < end) {
                    insert(data, position, head, previous)
                    position++
                }
            } else {
                writeLiteral(bits, data[position].toInt() and 0xFF)
                insert(data, position, head, previous)
                position++
            }
        }
        writeLiteral(bits, END_OF_BLOCK)
    }

    private fun insert(data: ByteArray, position: Int, head: IntArray, previous: IntArray) {
        if (position + MIN_MATCH > data.size) return
        val hash = hash(data, position)
        previous[position and WINDOW_MASK] = head[hash]
        head[hash] = position
    }

    private fun hash(data: ByteArray, at: Int): Int {
        val value = ((data[at].toInt() and 0xFF) shl 16) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            (data[at + 2].toInt() and 0xFF)
        return (value * HASH_MULTIPLIER) ushr (Int.SIZE_BITS - HASH_BITS)
    }

    /** A literal byte or the end-of-block marker, with DEFLATE's fixed code for it. */
    private fun writeLiteral(bits: BitSink, symbol: Int) {
        when {
            symbol <= 143 -> bits.writeHuffman(0x30 + symbol, 8)
            symbol <= 255 -> bits.writeHuffman(0x190 + symbol - 144, 9)
            symbol <= 279 -> bits.writeHuffman(symbol - 256, 7)
            else -> bits.writeHuffman(0xC0 + symbol - 280, 8)
        }
    }

    private fun writeLength(bits: BitSink, length: Int) {
        var code = LENGTH_BASE.size - 1
        while (LENGTH_BASE[code] > length) code--
        writeLiteral(bits, FIRST_LENGTH_SYMBOL + code)
        val extra = LENGTH_EXTRA[code]
        if (extra > 0) bits.writeBits(length - LENGTH_BASE[code], extra)
    }

    private fun writeDistance(bits: BitSink, distance: Int) {
        var code = DISTANCE_BASE.size - 1
        while (DISTANCE_BASE[code] > distance) code--
        bits.writeHuffman(code, DISTANCE_CODE_BITS)
        val extra = DISTANCE_EXTRA[code]
        if (extra > 0) bits.writeBits(distance - DISTANCE_BASE[code], extra)
    }

    private fun chunk(out: ByteSink, type: String, body: ByteArray) {
        val typeBytes = type.encodeToByteArray()
        out.writeInt(body.size)
        out.write(typeBytes)
        out.write(body)
        var crc = CRC_INITIAL
        crc = crc32(crc, typeBytes)
        crc = crc32(crc, body)
        out.writeInt(crc xor CRC_INITIAL)
    }

    private fun crc32(start: Int, bytes: ByteArray): Int {
        var crc = start
        for (byte in bytes) {
            crc = CRC_TABLE[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc
    }

    private fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % ADLER_MODULUS
            b = (b + a) % ADLER_MODULUS
        }
        return (b shl 16) or a
    }

    private val CRC_TABLE: IntArray = IntArray(256) { index ->
        var crc = index
        repeat(8) { crc = if (crc and 1 != 0) CRC_POLYNOMIAL xor (crc ushr 1) else crc ushr 1 }
        crc
    }

    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val DISTANCE_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DISTANCE_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )

    private const val BYTES_PER_PIXEL = 4
    private const val FILTER_NONE: Byte = 0
    private const val BIT_DEPTH = 8
    private const val COLOUR_TYPE_RGBA = 6
    private const val IHDR_LENGTH = 13
    private const val ZLIB_CMF = 0x78
    private const val ZLIB_FLG = 0x01
    private const val END_OF_BLOCK = 256
    private const val FIRST_LENGTH_SYMBOL = 257
    private const val DISTANCE_CODE_BITS = 5
    private const val MIN_MATCH = 3
    private const val MAX_MATCH = 258
    private const val MAX_CHAIN = 32
    private const val WINDOW_SIZE = 32_768
    private const val WINDOW_MASK = WINDOW_SIZE - 1
    private const val HASH_BITS = 15
    private const val HASH_SIZE = 1 shl HASH_BITS
    private const val HASH_MULTIPLIER = -0x61c88647
    private const val ADLER_MODULUS = 65_521
    private const val CRC_POLYNOMIAL = -0x12477ce0
    private const val CRC_INITIAL = -1

    /** A game frame compresses far better than this; it only sizes the first buffer. */
    private const val ESTIMATED_RATIO = 8
    private const val HEADER_ALLOWANCE = 64
}

/** A growable byte buffer, big-endian where it writes integers, as PNG wants. */
private class ByteSink(initialCapacity: Int) {
    private var bytes = ByteArray(maxOf(initialCapacity, 16))
    private var size = 0

    fun writeByte(value: Int) {
        if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
        bytes[size++] = value.toByte()
    }

    fun writeInt(value: Int) {
        writeByte(value ushr 24)
        writeByte(value ushr 16)
        writeByte(value ushr 8)
        writeByte(value)
    }

    fun write(values: ByteArray) {
        if (size + values.size > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + values.size))
        values.copyInto(bytes, size)
        size += values.size
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}

/** DEFLATE's bit order: values least significant bit first, Huffman codes most significant first. */
private class BitSink(initialCapacity: Int) {
    private val bytes = ByteSink(initialCapacity)
    private var buffer = 0
    private var count = 0

    fun writeBits(value: Int, width: Int) {
        for (bit in 0 until width) {
            buffer = buffer or (((value ushr bit) and 1) shl count)
            count++
            if (count == Byte.SIZE_BITS) {
                bytes.writeByte(buffer)
                buffer = 0
                count = 0
            }
        }
    }

    fun writeHuffman(code: Int, width: Int) {
        for (bit in width - 1 downTo 0) writeBits((code ushr bit) and 1, 1)
    }

    fun flushToByte() {
        if (count > 0) {
            bytes.writeByte(buffer)
            buffer = 0
            count = 0
        }
    }

    fun writeAlignedByte(value: Int) {
        check(count == 0) { "a byte-aligned write in the middle of a bit stream" }
        bytes.writeByte(value)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}
