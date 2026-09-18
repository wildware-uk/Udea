package dev.wildware.moba.render

import dev.wildware.udea.render.draw.SpriteTexture
import kotlin.math.abs

/**
 * Turns the PNG bytes an atlas page is packed as into the RGBA8888 a [SpriteTexture] takes.
 *
 * ## Why the game owns a decoder
 *
 * It always did; only the decoder moved. `.udeapak` stores each atlas page as PNG - `PackedAtlas`
 * says so in one line - and before issue #212 this step was `Pixmap(encoded, 0, encoded.size)`,
 * LibGDX's decoder, called from `CharacterRenderSystem`. LibGDX left with issue #211 and
 * `udea-render` does not expose one: Kool is an `implementation` dependency there, so nothing of
 * Kool's reaches a consumer's compile classpath, and `SpriteTexture.fromRgba(width, height, rgba)`
 * is the public surface the module *does* offer for exactly this hand-off. So the game decodes and
 * hands pixels over, which is the same division of labour as before.
 *
 * ## What it reads
 *
 * Every non-interlaced 8-bit PNG: greyscale, greyscale+alpha, truecolour, truecolour+alpha and
 * palette, with or without `tRNS`. That is a superset of what the packer writes - `AssetPackCli`
 * encodes through `ImageIO` from an ARGB image, which is colour type 6 - and the extra cases cost a
 * branch each and mean a hand-made PNG dropped into the sprite tree does not fail mysteriously.
 *
 * Anything outside that fails **loudly**, naming the bit depth, the colour type or the interlace
 * method it found. The alternative - decoding it wrong, or returning a blank page - is the shape of
 * bug this repository has already shipped once: a renderer that drew nothing looked like art
 * direction rather than like a decode failure.
 */
internal object Png {

    /** The eight bytes every PNG starts with. */
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    private const val BYTES_PER_PIXEL = 4
    private const val CHUNK_LENGTH_BYTES = 4
    private const val CHUNK_TYPE_BYTES = 4
    private const val CHUNK_CRC_BYTES = 4
    private const val IHDR_BYTES = 13
    private const val SUPPORTED_BIT_DEPTH = 8
    private const val NOT_INTERLACED = 0

    private const val GREY = 0
    private const val TRUECOLOUR = 2
    private const val PALETTE = 3
    private const val GREY_ALPHA = 4
    private const val TRUECOLOUR_ALPHA = 6

    private const val OPAQUE = 0xFF

    /** A decoded image: `width * height * 4` bytes, RGBA, top row first. */
    internal class Image(val width: Int, val height: Int, val rgba: ByteArray)

    /**
     * Decodes [png] and wraps it as a texture named [name].
     *
     * @throws IllegalArgumentException when [png] is not a PNG this can read, naming why.
     */
    fun texture(png: ByteArray, name: String): SpriteTexture {
        val image = decode(png, name)
        return SpriteTexture.fromRgba(image.width, image.height, image.rgba, name)
    }

    /** @throws IllegalArgumentException when [png] is not a PNG this can read, naming why. */
    fun decode(png: ByteArray, name: String): Image {
        require(png.size > SIGNATURE.size) { "'$name' is ${png.size} bytes, which is not a PNG" }
        for (index in SIGNATURE.indices) {
            require(png[index] == SIGNATURE[index]) {
                "'$name' does not start with the PNG signature"
            }
        }

        var width = 0
        var height = 0
        var colourType = -1
        var palette: ByteArray? = null
        var paletteAlpha: ByteArray? = null
        val compressed = ArrayList<ByteArray>()
        var at = SIGNATURE.size

        while (at + CHUNK_LENGTH_BYTES + CHUNK_TYPE_BYTES <= png.size) {
            val length = int32(png, at)
            val type = string(png, at + CHUNK_LENGTH_BYTES, CHUNK_TYPE_BYTES)
            val body = at + CHUNK_LENGTH_BYTES + CHUNK_TYPE_BYTES
            require(length >= 0 && body + length + CHUNK_CRC_BYTES <= png.size) {
                "'$name' declares a $length-byte '$type' chunk at $at, past the end of ${png.size} bytes"
            }
            when (type) {
                "IHDR" -> {
                    require(length == IHDR_BYTES) { "'$name' has a $length-byte IHDR" }
                    width = int32(png, body)
                    height = int32(png, body + 4)
                    val bitDepth = png[body + 8].toInt() and 0xFF
                    colourType = png[body + 9].toInt() and 0xFF
                    val interlace = png[body + 12].toInt() and 0xFF
                    require(bitDepth == SUPPORTED_BIT_DEPTH) {
                        "'$name' is $bitDepth bits per sample; this decoder reads $SUPPORTED_BIT_DEPTH"
                    }
                    require(interlace == NOT_INTERLACED) {
                        "'$name' is Adam7-interlaced; this decoder reads non-interlaced PNGs only"
                    }
                    require(width > 0 && height > 0) { "'$name' is ${width}x$height" }
                }
                "PLTE" -> palette = png.copyOfRange(body, body + length)
                "tRNS" -> paletteAlpha = png.copyOfRange(body, body + length)
                "IDAT" -> compressed += png.copyOfRange(body, body + length)
                "IEND" -> at = png.size
            }
            at = body + length + CHUNK_CRC_BYTES
        }

        require(colourType >= 0) { "'$name' has no IHDR chunk" }
        require(compressed.isNotEmpty()) { "'$name' has no IDAT chunk, so it carries no pixels" }

        val samples = samplesPerPixel(colourType, name)
        // One filter byte per scanline, then `width * samples` sample bytes.
        val stride = width * samples
        val raw = inflateZlib(join(compressed), (stride + 1) * height)
        unfilter(raw, height, samples, stride, name)
        return Image(width, height, toRgba(raw, width, height, samples, stride, colourType, palette, paletteAlpha, name))
    }

    private fun samplesPerPixel(colourType: Int, name: String): Int = when (colourType) {
        GREY -> 1
        TRUECOLOUR -> 3
        PALETTE -> 1
        GREY_ALPHA -> 2
        TRUECOLOUR_ALPHA -> 4
        else -> throw IllegalArgumentException("'$name' has PNG colour type $colourType, which is not one of 0, 2, 3, 4, 6")
    }

    /**
     * Undoes the per-scanline filters, in place.
     *
     * Each scanline in [raw] is one filter byte followed by [stride] sample bytes, and a filter
     * predicts a byte from its left neighbour, the byte above it, or both. Reconstructed in place
     * and in order, because every filter reads only bytes already reconstructed.
     */
    private fun unfilter(raw: ByteArray, height: Int, samples: Int, stride: Int, name: String) {
        for (row in 0 until height) {
            val line = row * (stride + 1)
            val filter = raw[line].toInt() and 0xFF
            val start = line + 1
            val above = start - (stride + 1)
            for (index in 0 until stride) {
                val here = start + index
                val left = if (index >= samples) raw[here - samples].toInt() and 0xFF else 0
                val up = if (row > 0) raw[above + index].toInt() and 0xFF else 0
                val upLeft = if (row > 0 && index >= samples) raw[above + index - samples].toInt() and 0xFF else 0
                val value = raw[here].toInt() and 0xFF
                raw[here] = when (filter) {
                    0 -> value
                    1 -> value + left
                    2 -> value + up
                    3 -> value + (left + up) / 2
                    4 -> value + paeth(left, up, upLeft)
                    else -> throw IllegalArgumentException(
                        "'$name' row $row uses PNG filter $filter, and there are only 0..4",
                    )
                }.toByte()
            }
        }
    }

    /** The PNG spec's Paeth predictor: whichever of the three neighbours the gradient is nearest. */
    private fun paeth(left: Int, up: Int, upLeft: Int): Int {
        val estimate = left + up - upLeft
        val distanceLeft = abs(estimate - left)
        val distanceUp = abs(estimate - up)
        val distanceUpLeft = abs(estimate - upLeft)
        return when {
            distanceLeft <= distanceUp && distanceLeft <= distanceUpLeft -> left
            distanceUp <= distanceUpLeft -> up
            else -> upLeft
        }
    }

    @Suppress("LongParameterList")
    private fun toRgba(
        raw: ByteArray,
        width: Int,
        height: Int,
        samples: Int,
        stride: Int,
        colourType: Int,
        palette: ByteArray?,
        paletteAlpha: ByteArray?,
        name: String,
    ): ByteArray {
        val out = ByteArray(width * height * BYTES_PER_PIXEL)
        var write = 0
        for (row in 0 until height) {
            var read = row * (stride + 1) + 1
            for (column in 0 until width) {
                when (colourType) {
                    GREY -> {
                        val grey = raw[read]
                        out[write] = grey
                        out[write + 1] = grey
                        out[write + 2] = grey
                        out[write + 3] = OPAQUE.toByte()
                    }
                    GREY_ALPHA -> {
                        val grey = raw[read]
                        out[write] = grey
                        out[write + 1] = grey
                        out[write + 2] = grey
                        out[write + 3] = raw[read + 1]
                    }
                    TRUECOLOUR -> {
                        out[write] = raw[read]
                        out[write + 1] = raw[read + 1]
                        out[write + 2] = raw[read + 2]
                        out[write + 3] = OPAQUE.toByte()
                    }
                    TRUECOLOUR_ALPHA -> {
                        out[write] = raw[read]
                        out[write + 1] = raw[read + 1]
                        out[write + 2] = raw[read + 2]
                        out[write + 3] = raw[read + 3]
                    }
                    PALETTE -> {
                        val table = requireNotNull(palette) { "'$name' is palette-indexed and has no PLTE chunk" }
                        val index = raw[read].toInt() and 0xFF
                        val entry = index * 3
                        require(entry + 2 < table.size) {
                            "'$name' names palette entry $index and its PLTE holds ${table.size / 3}"
                        }
                        out[write] = table[entry]
                        out[write + 1] = table[entry + 1]
                        out[write + 2] = table[entry + 2]
                        // `tRNS` on a palette image is one alpha per entry, and entries past its
                        // end are opaque - which is the spec's rule, not a fallback.
                        out[write + 3] =
                            if (paletteAlpha != null && index < paletteAlpha.size) paletteAlpha[index]
                            else OPAQUE.toByte()
                    }
                }
                read += samples
                write += BYTES_PER_PIXEL
            }
        }
        return out
    }

    private fun join(parts: List<ByteArray>): ByteArray {
        if (parts.size == 1) return parts[0]
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /** A big-endian unsigned 32-bit field, as an `Int`. PNG sizes never reach the sign bit. */
    private fun int32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun string(bytes: ByteArray, at: Int, length: Int): String =
        buildString(length) { for (index in 0 until length) append((bytes[at + index].toInt() and 0xFF).toChar()) }
}
