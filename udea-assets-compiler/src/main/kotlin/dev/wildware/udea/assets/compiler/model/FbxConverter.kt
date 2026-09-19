package dev.wildware.udea.assets.compiler.model

import dev.wildware.udea.assets.compiler.ResFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.lwjgl.assimp.AIScene
import org.lwjgl.assimp.Assimp
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Turns an `.fbx` into one self-contained binary glTF, at asset-build time (issue #244).
 *
 * Kool reads glTF and has no FBX loader, and FBX is a closed format, so a game that drops an
 * `.fbx` into its assets gets the `.glb` this makes: the same typed model asset a `.glb` is
 * (issue #240), with the same generated clips (issue #241). The converter is Assimp, through
 * LWJGL's binding, chosen over FBX2glTF by converting the sample with both; the comparison is on
 * issue #239. It runs in the asset compiler and nowhere else - UDEA-MG-013 keeps Assimp off every
 * runtime classpath.
 *
 * ### What is done to Assimp's `.glb`
 *
 * Assimp's glTF 2.0 exporter keeps the mesh, the skin and every take. Three things are then
 * changed, each for a reason a game would otherwise meet at run time:
 *
 * 1. **Every texture is embedded.** Assimp names a texture the FBX links as an external file;
 *    here the file is read from beside the `.fbx` and put into the binary buffer, so the `.glb`
 *    needs nothing else, and a texture that is not there fails the build rather than drawing a
 *    model with no skin.
 * 2. **The buffer is rewritten compacted.** Assimp leaves uninitialised memory in slack at the end
 *    of the buffer it writes, so two conversions of one file differ there, and the build cache
 *    keys on these bytes. Each buffer view is copied, in order, to a fresh buffer.
 * 3. **Each clip is named for its action.** Blender's exporter names each take
 *    `<armature>|<action>`, so the generated clip would be `HumanArmatureWalk`; [clipName] keeps
 *    what follows the last `|`, and the clip is `Human.Clips.Walk`.
 *
 * Every failure is a message completing "model `x` names `y`, which ...", returned rather than
 * thrown, because each caller turns it into a located `UDEA0039`.
 */
internal object FbxConverter {

    /**
     * Assimp's post-processing: triangles (glTF draws nothing else), shared vertices merged, at
     * most four bone weights a vertex (glTF's `JOINTS_0`/`WEIGHTS_0`), smooth normals where the
     * file has none, and Assimp's own check that the scene it built is consistent.
     */
    private const val IMPORT_FLAGS: Int = Assimp.aiProcess_Triangulate or
        Assimp.aiProcess_JoinIdenticalVertices or
        Assimp.aiProcess_LimitBoneWeights or
        Assimp.aiProcess_GenSmoothNormals or
        Assimp.aiProcess_ValidateDataStructure

    /** Assimp's id for its binary glTF 2.0 exporter. */
    private const val GLB_EXPORTER = "glb2"

    /** Buffer views start on a four-byte boundary, which every glTF component type needs. */
    private const val ALIGNMENT = 4

    private const val CLIP_SEPARATOR = '|'

    /**
     * The `.glb` that [file], an `.fbx` under [assetRoot], converts to, or a failure saying why it
     * does not. The same file always converts to the same bytes.
     */
    fun convert(assetRoot: Path, file: ResFile): Result<ByteArray> {
        val exported = export(assetRoot.resolve(file.value)).getOrElse { return Result.failure(it) }
        val container = GlbContainer.read(exported).getOrElse { reason ->
            return failure("was converted by Assimp into a file that ${reason.message}")
        }
        val folder = file.value.substringBeforeLast('/', missingDelimiterValue = "")
        return rewrite(container) { uri -> texture(assetRoot, folder, uri) }.map { it.write() }
    }

    /**
     * The clip name for an FBX take called [take]: what follows its last `|`, or the whole of it
     * when there is no `|` or nothing follows one.
     */
    fun clipName(take: String): String = take.substringAfterLast(CLIP_SEPARATOR).ifEmpty { take }

    /**
     * Assimp's `.glb` for [source]. Synchronised because Assimp's last-error string is global to
     * the native library, and two conversions at once would each report the other's reason.
     */
    @Synchronized
    private fun export(source: Path): Result<ByteArray> {
        val scene: AIScene = Assimp.aiImportFile(source.toString(), IMPORT_FLAGS)
            ?: return failure("could not be read as FBX: ${Assimp.aiGetErrorString().orEmpty().trim()}")
        try {
            if (scene.mFlags() and Assimp.AI_SCENE_FLAGS_INCOMPLETE != 0) {
                return failure("could not be read as FBX: Assimp read only part of it")
            }
            if (scene.mNumMeshes() == 0) return failure("holds no mesh, so there is nothing to draw")
            val blob = Assimp.aiExportSceneToBlob(scene, GLB_EXPORTER, 0)
                ?: return failure("could not be written as glTF: ${Assimp.aiGetErrorString().orEmpty().trim()}")
            try {
                val bytes = ByteArray(Math.toIntExact(blob.size()))
                blob.data().get(bytes)
                return Result.success(bytes)
            } finally {
                Assimp.aiReleaseExportBlob(blob)
            }
        } finally {
            Assimp.aiReleaseImport(scene)
        }
    }

    /** A texture's bytes and the type glTF names it by. */
    private class Texture(val bytes: ByteArray, val mimeType: String)

    /**
     * The texture a material names by [uri], read from under [folder], the `.fbx`'s own folder.
     *
     * An FBX may carry the path it was exported from, absolute and on another machine, so the file
     * is looked for at [uri] relative to the `.fbx` and then by its name alone beside it.
     */
    private fun texture(assetRoot: Path, folder: String, uri: String): Result<Texture> {
        val name = uri.replace('\\', '/').substringAfterLast('/')
        val candidates = listOf(uri.replace('\\', '/'), name)
            .filterNot { it.startsWith('/') || it.contains(':') }
            .map { ResFile.of(if (folder.isEmpty()) it else "$folder/$it") }
            .filterNot { it.isMalformed }
            .distinct()
        if (candidates.isEmpty()) return failure("names the texture `$uri`, which is not a file name")
        val found = candidates.firstOrNull { assetRoot.resolve(it.value).isRegularFile() }
            ?: return failure(
                "names the texture `$uri`, and there is no " +
                    candidates.joinToString(" or ") { "`$it`" } + " under the asset root",
            )
        val bytes = assetRoot.resolve(found.value).readBytes()
        val mimeType = mimeTypeOf(bytes)
            ?: return failure("names the texture `$uri`, and `$found` is not PNG or JPEG, the two image formats glTF 2.0 carries")
        return Result.success(Texture(bytes, mimeType))
    }

    private fun mimeTypeOf(bytes: ByteArray): String? = when {
        bytes.startsWith(PNG_SIGNATURE) -> "image/png"
        bytes.startsWith(JPEG_SIGNATURE) -> "image/jpeg"
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /**
     * [container] with its buffer compacted, every external texture embedded through [load], and
     * every clip renamed by [clipName].
     */
    private fun rewrite(container: GlbContainer, load: (String) -> Result<Texture>): Result<GlbContainer> {
        val document = container.json
        val buffers = document["buffers"] as? JsonArray ?: JsonArray(emptyList())
        if (buffers.size > 1) return failure("was converted by Assimp into a glTF with ${buffers.size} buffers, and a .glb holds one")

        val bin = ByteArrayOutputStream()
        val views = ArrayList<JsonElement>()
        for (view in (document["bufferViews"] as? JsonArray).orEmpty()) {
            val fields = view as? JsonObject ?: return failure("was converted by Assimp into a glTF with a buffer view that is not an object")
            val offset = fields.int("byteOffset") ?: 0
            val length = fields.int("byteLength")
                ?: return failure("was converted by Assimp into a glTF with a buffer view of no length")
            if (offset < 0 || length < 0 || offset + length > container.bin.size) {
                return failure("was converted by Assimp into a glTF whose buffer view runs past its buffer")
            }
            views += fields.with("byteOffset", JsonPrimitive(append(bin, container.bin, offset, length)))
        }

        val images = ArrayList<JsonElement>()
        for (image in (document["images"] as? JsonArray).orEmpty()) {
            val fields = image as? JsonObject
            val uri = (fields?.get("uri") as? JsonPrimitive)?.content
            if (fields == null || uri == null) {
                images += image
                continue
            }
            val texture = load(uri).getOrElse { return Result.failure(it) }
            val view = views.size
            views += JsonObject(
                linkedMapOf(
                    "buffer" to JsonPrimitive(0),
                    "byteOffset" to JsonPrimitive(append(bin, texture.bytes, 0, texture.bytes.size)),
                    "byteLength" to JsonPrimitive(texture.bytes.size),
                ),
            )
            images += JsonObject(
                LinkedHashMap(fields).apply {
                    remove("uri")
                    put("bufferView", JsonPrimitive(view))
                    put("mimeType", JsonPrimitive(texture.mimeType))
                },
            )
        }

        val animations = (document["animations"] as? JsonArray).orEmpty().map { animation ->
            val fields = animation as? JsonObject
            val name = (fields?.get("name") as? JsonPrimitive)?.content
            if (fields == null || name == null) animation else fields.with("name", JsonPrimitive(clipName(name)))
        }

        val compacted = bin.toByteArray()
        val rewritten = LinkedHashMap(document).apply {
            if (compacted.isNotEmpty()) put("buffers", JsonArray(listOf(JsonObject(mapOf("byteLength" to JsonPrimitive(compacted.size))))))
            if (views.isNotEmpty()) put("bufferViews", JsonArray(views))
            if (images.isNotEmpty()) put("images", JsonArray(images))
            if (animations.isNotEmpty()) put("animations", JsonArray(animations))
        }
        return Result.success(GlbContainer(JsonObject(rewritten), compacted))
    }

    /** Copies [length] bytes of [from] at [offset] onto [to], four-byte aligned, and says where they landed. */
    private fun append(to: ByteArrayOutputStream, from: ByteArray, offset: Int, length: Int): Int {
        while (to.size() % ALIGNMENT != 0) to.write(0)
        val at = to.size()
        to.write(from, offset, length)
        return at
    }

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject =
        JsonObject(LinkedHashMap(this).apply { put(key, value) })

    private fun <T> failure(why: String): Result<T> = Result.failure(IllegalArgumentException(why))
}
