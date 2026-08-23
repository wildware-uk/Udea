package dev.wildware.udea.assets.compiler.atlas

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** An 8-bit RGBA image in memory, row-major, non-premultiplied. */
public class RgbaImage(
    public val width: Int,
    public val height: Int,
    /** `width * height` pixels as `0xAARRGGBB`, the layout `BufferedImage.getRGB` produces. */
    public val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "an image is ${width}x$height" }
        require(argb.size == width * height) {
            "a ${width}x$height image needs ${width * height} pixels; got ${argb.size}"
        }
    }

    public operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    /** Copies [source]'s rectangle into this image at ([x], [y]). */
    public fun blit(source: RgbaImage, x: Int, y: Int, sx: Int = 0, sy: Int = 0, w: Int = source.width, h: Int = source.height) {
        require(x >= 0 && y >= 0 && x + w <= width && y + h <= height) {
            "blitting ${w}x$h to ($x, $y) does not fit in ${width}x$height"
        }
        for (row in 0 until h) {
            System.arraycopy(source.argb, (sy + row) * source.width + sx, argb, (y + row) * width + x, w)
        }
    }

    public companion object {
        /** A fully transparent image. Transparent, not black: the padding between frames. */
        public fun blank(width: Int, height: Int): RgbaImage = RgbaImage(width, height, IntArray(width * height))
    }
}

/**
 * A PNG encoder that produces the same bytes for the same pixels, on any machine, at any time.
 *
 * ## Why not `ImageIO.write`
 *
 * `ImageIO`'s PNG writer is not part of any specification about its output bytes. It has emitted
 * different IDAT segmentation across JDK versions, and it writes chunks (`tEXt` with a software
 * name, `tIME`, `pHYs`) that either vary between machines or record when the file was made.
 * Issue #89 asks for those chunks to be *stripped after encoding*; [PngChunks.strip] does that,
 * and exists for PNGs that arrive from elsewhere. For pages this build makes, never writing them
 * is better than stripping them, because it also pins the parts of the file no stripper reaches.
 *
 * ## The three degrees of freedom this pins
 *
 * 1. **Filtering.** The PNG spec lets an encoder pick a filter per scanline. Every heuristic in
 *    the wild is a different function. This one uses filter type 4 (Paeth) on every row, always.
 * 2. **Compression.** `Deflater` at a fixed level with the default strategy. Java's `Deflater`
 *    is `java.util.zip`'s bundled zlib, whose output for a given (level, strategy, input) has
 *    been stable across the JDK versions this project builds on - but it is the one thing here
 *    whose stability is *empirical* rather than structural. `ReproducibilityTest` compares two
 *    packs in one JVM, so it would not catch a JDK upgrade changing zlib. The check that would
 *    is comparing against a checked-in golden hash, and that is deliberately not done: it would
 *    fail the build on a JDK upgrade that changed nothing about the pixels. What the pipeline
 *    actually needs is that a *given* toolchain reproduces, and the toolchain is pinned to 17.
 * 3. **Chunk set.** Exactly `IHDR`, `IDAT`, `IEND`. Nothing else, ever.
 */
public object Png {

    /** The eight bytes every PNG starts with. */
    public val SIGNATURE: ByteArray
        get() = byteArrayOf(-119, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Deflate level. Pinned so the bytes cannot depend on a default that moved. */
    public const val COMPRESSION_LEVEL: Int = Deflater.BEST_COMPRESSION

    /** PNG filter type 4. Chosen once for every row rather than per row by a heuristic. */
    public const val FILTER_PAETH: Int = 4

    /** Bytes per pixel of the only colour type this writes: 8-bit RGBA. */
    public const val BYTES_PER_PIXEL: Int = 4

    private const val COLOR_TYPE_RGBA = 6
    private const val BIT_DEPTH = 8

    /** Encodes [image] as a PNG. */
    public fun encode(image: RgbaImage): ByteArray {
        val out = ByteArrayOutputStream(image.width * image.height)
        out.write(SIGNATURE)
        out.write(chunk("IHDR", ihdr(image.width, image.height)))
        out.write(chunk("IDAT", deflate(filter(image))))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun ihdr(width: Int, height: Int): ByteArray = ByteArrayOutputStream(13).apply {
        writeInt(width)
        writeInt(height)
        write(BIT_DEPTH)
        write(COLOR_TYPE_RGBA)
        write(0) // compression method: deflate, the only one defined
        write(0) // filter method: adaptive, the only one defined
        write(0) // interlace: none. Adam7 would be a second encoding of the same pixels.
    }.toByteArray()

    /**
     * Applies the Paeth filter to every scanline.
     *
     * The filter is what makes a run of identical pixels compress: it stores each byte as a
     * difference from a prediction made out of the pixel to the left, the one above, and the one
     * above-left. On a sprite atlas - large transparent margins, repeated outlines - the
     * difference between filtered and unfiltered is several times the file size.
     */
    private fun filter(image: RgbaImage): ByteArray {
        val stride = image.width * BYTES_PER_PIXEL
        val out = ByteArray(image.height * (stride + 1))
        val current = ByteArray(stride)
        val previous = ByteArray(stride)
        var at = 0
        for (y in 0 until image.height) {
            var i = 0
            for (x in 0 until image.width) {
                val pixel = image[x, y]
                current[i++] = ((pixel ushr 16) and 0xFF).toByte() // R
                current[i++] = ((pixel ushr 8) and 0xFF).toByte() // G
                current[i++] = (pixel and 0xFF).toByte() // B
                current[i++] = ((pixel ushr 24) and 0xFF).toByte() // A
            }
            out[at++] = FILTER_PAETH.toByte()
            for (b in 0 until stride) {
                val left = if (b >= BYTES_PER_PIXEL) current[b - BYTES_PER_PIXEL].toInt() and 0xFF else 0
                val up = previous[b].toInt() and 0xFF
                val upLeft = if (b >= BYTES_PER_PIXEL) previous[b - BYTES_PER_PIXEL].toInt() and 0xFF else 0
                out[at++] = ((current[b].toInt() and 0xFF) - paeth(left, up, upLeft)).toByte()
            }
            System.arraycopy(current, 0, previous, 0, stride)
        }
        return out
    }

    /** The PNG spec's PaethPredictor, verbatim. */
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(COMPRESSION_LEVEL)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 4 + DEFLATE_BUFFER)
            val buffer = ByteArray(DEFLATE_BUFFER)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                out.write(buffer, 0, written)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private const val DEFLATE_BUFFER = 1 shl 16

    /** length, type, data, CRC32 of (type + data). */
    internal fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        require(typeBytes.size == 4) { "a PNG chunk type is four ASCII bytes; '$type' is not" }
        val out = ByteArrayOutputStream(data.size + 12)
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.writeInt(crc.value.toInt())
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }
}
