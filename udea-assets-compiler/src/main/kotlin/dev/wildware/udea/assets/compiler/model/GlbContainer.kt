package dev.wildware.udea.assets.compiler.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A binary glTF 2.0 file (`.glb`) as its two chunks: the JSON document and the binary buffer.
 *
 * The one reader of the container in this module. [ModelFileValidator][dev.wildware.udea.assets.compiler.validate.ModelFileValidator]
 * and `GltfClips` read only the JSON, through [jsonText]; the FBX converter reads both chunks and
 * writes a new file with [write] (issue #244).
 *
 * @property json the glTF document.
 * @property bin the binary chunk's bytes, without its padding; empty when the file has none.
 */
internal class GlbContainer(val json: JsonObject, val bin: ByteArray) {

    /**
     * This container as a `.glb`: the header, the JSON chunk padded with spaces and the binary
     * chunk padded with zeros, each to a multiple of four bytes as the container format requires.
     * A container with no binary bytes is written with no binary chunk.
     */
    fun write(): ByteArray {
        val text = Json.encodeToString(JsonObject.serializer(), json).encodeToByteArray()
        val jsonLength = padded(text.size)
        val binLength = padded(bin.size)
        val total = HEADER_BYTES + CHUNK_HEADER_BYTES + jsonLength +
            if (bin.isEmpty()) 0 else CHUNK_HEADER_BYTES + binLength
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC).putInt(VERSION).putInt(total)
        out.putInt(jsonLength).putInt(JSON_CHUNK).put(text)
        repeat(jsonLength - text.size) { out.put(JSON_PAD) }
        if (bin.isNotEmpty()) {
            out.putInt(binLength).putInt(BIN_CHUNK).put(bin)
            repeat(binLength - bin.size) { out.put(0) }
        }
        return out.array()
    }

    companion object {
        /** `glTF`, little-endian. */
        private const val MAGIC = 0x46546C67

        /** `JSON`, little-endian: the type of a `.glb`'s first chunk. */
        private const val JSON_CHUNK = 0x4E4F534A

        /** `BIN\0`, little-endian: the type of the binary chunk that may follow it. */
        private const val BIN_CHUNK = 0x004E4942

        private const val VERSION = 2

        /** Magic, version and length. */
        private const val HEADER_BYTES = 12

        /** A chunk's length and type. */
        private const val CHUNK_HEADER_BYTES = 8

        private const val JSON_PAD: Byte = 0x20

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The JSON chunk of a binary glTF, or a failure whose message completes "the file ..." and
         * says why [bytes] is not one.
         */
        fun jsonText(bytes: ByteArray): Result<String> = jsonChunk(bytes).map { (text, _) -> text }

        /**
         * Both chunks of [bytes], or a failure as [jsonText] words it, or one saying the JSON does
         * not parse or the chunk after it is not the binary chunk.
         */
        fun read(bytes: ByteArray): Result<GlbContainer> {
            val (text, end) = jsonChunk(bytes).getOrElse { return Result.failure(it) }
            val bin = binChunk(bytes, end).getOrElse { return Result.failure(it) }
            val document = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                // `parseToJsonElement` reports malformed input this way; the failure below says so.
                null
            } ?: return notGlb("its JSON chunk does not parse as a JSON object")
            return Result.success(GlbContainer(document, bin))
        }

        /** The JSON chunk's text and the offset just past it. */
        private fun jsonChunk(bytes: ByteArray): Result<Pair<String, Int>> {
            if (bytes.size < HEADER_BYTES + CHUNK_HEADER_BYTES) {
                return notGlb("it is ${bytes.size} bytes, shorter than a binary glTF header")
            }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = header.getInt(0)
            val version = header.getInt(4)
            val length = header.getInt(8)
            val chunkLength = header.getInt(12)
            val chunkType = header.getInt(16)
            val jsonStart = HEADER_BYTES + CHUNK_HEADER_BYTES
            return when {
                magic != MAGIC -> notGlb("its first four bytes are not `glTF`")
                version != VERSION -> notGlb("its container version is $version, not $VERSION")
                length != bytes.size -> notGlb("its header says $length bytes and the file is ${bytes.size}")
                chunkType != JSON_CHUNK -> notGlb("its first chunk is not JSON")
                chunkLength < 0 || jsonStart + chunkLength > bytes.size ->
                    notGlb("its JSON chunk runs past the end of the file")
                else -> Result.success(
                    bytes.decodeToString(jsonStart, jsonStart + chunkLength) to jsonStart + chunkLength,
                )
            }
        }

        /** The binary chunk starting at [at], or an empty one when the file ends there. */
        private fun binChunk(bytes: ByteArray, at: Int): Result<ByteArray> {
            if (at == bytes.size) return Result.success(ByteArray(0))
            if (at + CHUNK_HEADER_BYTES > bytes.size) return notGlb("its second chunk header runs past the end of the file")
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val length = header.getInt(at)
            val type = header.getInt(at + 4)
            val start = at + CHUNK_HEADER_BYTES
            return when {
                type != BIN_CHUNK -> notGlb("its second chunk is not BIN")
                length < 0 || start + length > bytes.size -> notGlb("its BIN chunk runs past the end of the file")
                else -> Result.success(bytes.copyOfRange(start, start + length))
            }
        }

        private fun padded(size: Int): Int = (size + 3) and 3.inv()

        private fun <T> notGlb(why: String): Result<T> =
            Result.failure(IllegalArgumentException("is not a binary glTF 2.0 file: $why"))
    }
}
