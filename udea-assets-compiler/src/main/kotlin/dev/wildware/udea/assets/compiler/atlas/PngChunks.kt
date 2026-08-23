package dev.wildware.udea.assets.compiler.atlas

import java.io.ByteArrayOutputStream

/**
 * Reads and rewrites a PNG's chunk stream.
 *
 * Issue #89 asks for `tIME`, `tEXt` and `pHYs` to be stripped after encoding. [Png] never writes
 * them, so for pages this build makes the strip is a no-op - which is exactly what
 * `PngDeterminismTest` asserts, so that "we strip them" cannot quietly become "we never checked".
 * The stripper is still load-bearing: source art on disk arrives from art tools that stamp all
 * three, and the source PNGs go into the bundle unchanged when they are not atlased.
 */
public object PngChunks {

    /**
     * Chunks that survive a strip.
     *
     * Everything critical (uppercase first letter) plus `tRNS`, which is ancillary by the
     * spec's casing rule but is the alpha channel of a palettised image, so dropping it would
     * change what the file looks like. Everything else is metadata: the software that wrote it,
     * when, at what physical resolution, on what gamma - none of which a texture atlas means
     * anything by, and two of which vary between builds.
     */
    public val KEPT: Set<String> = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS")

    /** Chunk types that carry build-varying metadata. Present for a named, testable claim. */
    public val NON_REPRODUCIBLE: Set<String> = setOf("tIME", "tEXt", "iTXt", "zTXt", "pHYs")

    /** One chunk, as it sits in the file. */
    public data class Chunk(public val type: String, public val data: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Chunk && type == other.type && data.contentEquals(other.data))

        override fun hashCode(): Int = 31 * type.hashCode() + data.contentHashCode()

        override fun toString(): String = "Chunk($type, ${data.size} bytes)"
    }

    /** Every chunk in [png], in file order. */
    public fun read(png: ByteArray): List<Chunk> {
        require(png.size > Png.SIGNATURE.size) { "a PNG is longer than its signature" }
        require(png.copyOfRange(0, Png.SIGNATURE.size).contentEquals(Png.SIGNATURE)) {
            "these bytes do not start with the PNG signature"
        }
        val chunks = mutableListOf<Chunk>()
        var at = Png.SIGNATURE.size
        while (at + CHUNK_OVERHEAD <= png.size) {
            val length = readInt(png, at)
            require(length >= 0 && at + CHUNK_OVERHEAD + length <= png.size) {
                "chunk at $at claims $length bytes, past the ${png.size}-byte end of the file"
            }
            val type = String(png, at + 4, 4, Charsets.US_ASCII)
            chunks += Chunk(type, png.copyOfRange(at + 8, at + 8 + length))
            at += CHUNK_OVERHEAD + length
        }
        return chunks
    }

    /** Chunk type names in [png], in file order. */
    public fun typesIn(png: ByteArray): List<String> = read(png).map { it.type }

    /**
     * [png] with everything outside [KEPT] removed and every CRC recomputed.
     *
     * The CRC recomputation is why this rewrites rather than splices: a chunk's CRC covers its
     * own type and data only, so removing whole chunks would leave the survivors' CRCs valid -
     * but a future strip that *edited* a chunk would not, and a stripper that produces an
     * invalid file in one of its two modes is a trap.
     */
    public fun strip(png: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(png.size)
        out.write(Png.SIGNATURE)
        read(png).filter { it.type in KEPT }.forEach { out.write(Png.chunk(it.type, it.data)) }
        return out.toByteArray()
    }

    private const val CHUNK_OVERHEAD = 4 + 4 + 4

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
