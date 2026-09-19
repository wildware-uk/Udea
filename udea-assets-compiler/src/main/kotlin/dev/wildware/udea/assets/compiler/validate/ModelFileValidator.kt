package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.model.FbxConverter
import dev.wildware.udea.assets.compiler.model.GlbContainer
import dev.wildware.udea.assets.compiler.model.ModelSources
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * A `model(...)` names a file that is there but is not a glTF 2.0 model (issue #240).
 *
 * The file is read at build time so that a game never gets as far as the renderer with a model it
 * cannot draw: Kool's loader, which does the drawing, reports a bad file from a coroutine on the
 * render thread, long after the build said yes.
 *
 * ### What it checks, in order
 *
 * 1. The extension is one of [Model.EXTENSIONS], or `.fbx`. An `.fbx` is converted instead, by
 *    [FbxConverter], and a file that does not convert is `UDEA0039` (issue #244) rather than
 *    the steps below: the `.glb` the converter writes is Assimp's, compacted, and the checks
 *    here are about files a person wrote.
 * 2. A `.glb` has the binary glTF header - magic `glTF`, container version 2, a length equal to
 *    the file's - and its first chunk is JSON.
 * 3. The JSON (the whole of a `.gltf`, or the `.glb`'s first chunk) is an object whose
 *    `asset.version` is `2.x`.
 * 4. Every buffer and image the JSON names by a relative `uri` is a file inside the asset root. A
 *    `data:` URI and a `.glb`'s own binary chunk carry their bytes inline and need nothing.
 *
 * The first thing wrong is reported and the rest is not read: each step assumes the one before it
 * held.
 *
 * ### What it does not do
 *
 * A file that is absent is [MissingFileValidator]'s `UDEA0032`, which already offers a did-you-mean,
 * and is skipped here so one defect is not two diagnostics. It does not decode meshes or images:
 * a corrupt PNG inside a valid container is the renderer's to find, and it fails loudly there
 * (`ModelLoadException`) rather than drawing a substitute.
 */
public object ModelFileValidator : AssetValidator {

    /** The DSL word whose declarations are models. */
    public const val KIND: String = "model"

    /** The declaration field that names the file. */
    public const val FILE_FIELD: String = "file"

    override val rules: List<UdeaRule> = listOf(AssetValidationRules.MODEL_FILE, AssetValidationRules.MODEL_CONVERSION)

    override fun validate(context: ValidationContext): List<UdeaDiagnostic> =
        context.graph.assets.values
            .filter { it.kind == KIND }
            .sortedBy { it.id }
            .mapNotNull { model ->
                val path = model.fields[FILE_FIELD] as? ResFile ?: return@mapNotNull null
                if (path.isMalformed) return@mapNotNull null
                val file = context.fileOf(path)
                if (!file.isRegularFile()) return@mapNotNull null
                val (rule, problem) = if (ModelSources.isConverted(path)) {
                    val failure = FbxConverter.convert(context.assetRoot, path).exceptionOrNull() ?: return@mapNotNull null
                    AssetValidationRules.MODEL_CONVERSION to failure.message
                } else {
                    AssetValidationRules.MODEL_FILE to (GltfCheck.problemWith(path, file, context::fileOf) ?: return@mapNotNull null)
                }
                rule.diagnostic(
                    message = "model `${model.id}` names `$path`, which $problem",
                    span = context.spanFor(model),
                    assetId = model.id,
                )
            }
}

/** The four checks [ModelFileValidator] makes, each answering what is wrong or `null`. */
internal object GltfCheck {

    private const val DATA_URI = "data:"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * What is wrong with the model file [file], named [path] in the script, or `null` if it is a
     * glTF 2.0 file whose external files are all present. [resolve] places a relative URI in the
     * asset root.
     */
    fun problemWith(path: ResFile, file: Path, resolve: (ResFile) -> Path): String? {
        val extension = path.value.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension !in Model.EXTENSIONS) {
            return "is not a model file: a model is glTF 2.0, a .glb or .gltf file, or an .fbx the build converts to one"
        }
        val text = jsonOf(extension, file.readBytes()).getOrElse { return it.message }
        val document = parse(text) ?: return "is not a glTF 2.0 file: its JSON does not parse"
        val version = ((document["asset"] as? JsonObject)?.get("version") as? JsonPrimitive)?.content
        if (version == null || !version.startsWith("2.")) {
            return "is glTF version ${version ?: "(none stated)"}, and only glTF 2.0 is supported"
        }
        val folder = path.value.substringBeforeLast('/', missingDelimiterValue = "")
        val missing = externalUris(document)
            .map { uri ->
                val relative = decoded(uri)
                uri to ResFile.of(if (folder.isEmpty()) relative else "$folder/$relative")
            }
            .filter { (_, target) -> target.isMalformed || !resolve(target).isRegularFile() }
        if (missing.isNotEmpty()) {
            return "names files that are not under the asset root: " +
                missing.joinToString(", ") { (uri, target) -> "`$uri` (`$target`)" }
        }
        return null
    }

    /**
     * The glTF JSON of a file with [extension]: the whole of a `.gltf`, or a `.glb`'s JSON chunk,
     * or a failure whose message completes "the file ...". Shared with `GltfClips`, so the
     * validator and the clip reader cannot disagree about what a glTF file is.
     */
    fun jsonOf(extension: String, bytes: ByteArray): Result<String> =
        if (extension == "glb") GlbContainer.jsonText(bytes) else Result.success(bytes.decodeToString())

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        // `parseToJsonElement` reports malformed input this way; the caller turns `null` into
        // the diagnostic, so nothing is lost.
        null
    }

    /** Every `uri` in `buffers` and `images` that names a file rather than carrying its bytes. */
    private fun externalUris(document: JsonObject): List<String> =
        listOf("buffers", "images").flatMap { key ->
            (document[key] as? JsonArray).orEmpty().mapNotNull { entry -> uriOf(entry) }
        }.filterNot { it.startsWith(DATA_URI) }

    /**
     * The file a relative URI names: glTF URIs are percent-encoded (`Fox%20Skin.png`). A URI too
     * malformed to decode is returned as written, and then names no file, which is reported.
     */
    private fun decoded(uri: String): String = try {
        URI(uri).path ?: uri
    } catch (_: URISyntaxException) {
        uri
    }

    private fun uriOf(entry: JsonElement): String? =
        ((entry as? JsonObject)?.get("uri") as? JsonPrimitive)?.content
}
