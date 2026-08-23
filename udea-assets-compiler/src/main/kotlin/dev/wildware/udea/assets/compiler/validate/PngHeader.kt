package dev.wildware.udea.assets.compiler.validate

import java.nio.file.Path
import kotlin.io.path.inputStream

/** The pixel dimensions of an image. */
public data class ImageSize(public val width: Int, public val height: Int)

/**
 * Reads the width and height out of a PNG's IHDR chunk, and nothing else.
 *
 * **Twenty-four bytes.** Not `ImageIO.read`, which decodes the whole image: a validator that
 * decoded every sheet in a project would turn a sub-second check into a memory-bound one, and
 * it would fail on a file whose *pixels* are corrupt when the only question asked is how wide
 * it is. `TruncatedPngTest` proves the distinction by validating a PNG truncated immediately
 * after the header.
 *
 * The layout is fixed by the PNG spec and cannot drift: bytes 0..7 are the signature, 8..11 the
 * IHDR length, 12..15 the literal chunk type `IHDR`, 16..19 the width and 20..23 the height,
 * both big-endian unsigned 32-bit.
 */
public object PngHeader {

    /** How many bytes [read] ever consumes. */
    public const val HEADER_BYTES: Int = 24

    private val SIGNATURE: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private val IHDR: ByteArray = byteArrayOf(0x49, 0x48, 0x44, 0x52)

    /**
     * The dimensions of the PNG at [file], or `null` when it is not one.
     *
     * `null` covers a file that is absent, shorter than [HEADER_BYTES], not signed as a PNG, or
     * whose first chunk is not `IHDR`. The caller decides what that means — a missing file is
     * [MissingFileValidator]'s diagnostic, not this one's.
     */
    public fun read(file: Path): ImageSize? {
        val header = try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(HEADER_BYTES)
                var read = 0
                while (read < HEADER_BYTES) {
                    val n = stream.read(buffer, read, HEADER_BYTES - read)
                    if (n < 0) break
                    read += n
                }
                if (read < HEADER_BYTES) null else buffer
            }
        } catch (_: java.io.IOException) {
            null
        } ?: return null

        for (i in SIGNATURE.indices) if (header[i] != SIGNATURE[i]) return null
        for (i in IHDR.indices) if (header[12 + i] != IHDR[i]) return null

        val width = beInt(header, 16)
        val height = beInt(header, 20)
        // A PNG dimension is a *non-zero* unsigned 32-bit int; anything that does not fit a
        // positive signed int is not something this build could load either, so it reads as
        // "not a PNG this validator can measure" rather than as a negative width.
        if (width <= 0 || height <= 0) return null
        return ImageSize(width, height)
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
