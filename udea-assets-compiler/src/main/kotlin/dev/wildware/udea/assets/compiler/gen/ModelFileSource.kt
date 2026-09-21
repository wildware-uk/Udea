package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.model.FbxConverter
import dev.wildware.udea.assets.compiler.model.ModelSources
import dev.wildware.udea.assets.compiler.scan.Declaration
import dev.wildware.udea.assets.compiler.validate.AssetValidationRules
import dev.wildware.udea.assets.compiler.validate.ModelFileValidator
import dev.wildware.udea.diagnostics.DidYouMean
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/** What [ModelFileSource] found in each model's file, and what stopped the rest. */
internal class ModelFileScan(
    /** Asset id to that model's clips, in file order. A model that failed is absent. */
    val clips: Map<String, List<GltfClip>>,
    /** Asset id to that model's named nodes, in file order. A model that failed is absent. */
    val nodes: Map<String, List<GltfNode>>,
    val diagnostics: List<UdeaDiagnostic>,
)

/**
 * What every `model(...)` in a scan holds, read from the files they name: the animation clips
 * (issue #241) and the named nodes a part can be mounted on (issue #260).
 *
 * This is the accessors pass's one look at the asset tree, and it looks at the model files and
 * nothing else: the rest of what it generates comes from the scan alone. It runs before any game
 * code compiles, so a model whose file cannot be read is reported here, under
 * [AssetCompilerRules.MODEL_CLIPS], rather than left to surface as every `Fox.Clips.Run` and
 * `Fox.Nodes.b_Head_05` failing to resolve.
 *
 * Both readings come off one visit to the file, so an `.fbx` is converted once and the clips and
 * the nodes can never be read from two different states of a file somebody is editing.
 *
 * It is also the one reading the packed asset gets its nodes and extras from (issue #271):
 * [ModelContents] calls [readFile] for every `model(...)` in pass 2, so the list a game reads off
 * a `Ref<Model>` and the `Chassis.Nodes` accessors it compiles against come from the same code
 * over the same file, and cannot disagree.
 */
internal object ModelFileSource {

    /** Reads every model among [declarations], resolving the files they name against [assetRoot]. */
    fun read(assetRoot: Path, declarations: List<Declaration>): ModelFileScan {
        val clips = LinkedHashMap<String, List<GltfClip>>()
        val nodes = LinkedHashMap<String, List<GltfNode>>()
        val diagnostics = ArrayList<UdeaDiagnostic>()
        val models = declarations.filter { it.kind == ModelFileValidator.KIND }.distinctBy { it.id }.sortedBy { it.id }
        for (model in models) {
            readOne(assetRoot, model).fold(
                onSuccess = { contents ->
                    clips[model.id] = contents.clips
                    nodes[model.id] = contents.nodes
                },
                onFailure = { problem ->
                    val rule = if (problem is ConversionFailure) AssetValidationRules.MODEL_CONVERSION else AssetCompilerRules.MODEL_CLIPS
                    diagnostics += rule.diagnostic(
                        message = "model `${model.id}` ${problem.message}",
                        span = model.span,
                        assetId = model.id,
                    )
                },
            )
        }
        return ModelFileScan(clips, nodes, diagnostics)
    }

    /** One model's clips, its named nodes and its own extras, read from the same bytes. */
    internal class Contents(val clips: List<GltfClip>, val nodes: List<GltfNode>, val extras: Map<String, Any>)

    /** One model's contents, or a failure whose message completes "model `<id>` ...". */
    private fun readOne(assetRoot: Path, model: Declaration): Result<Contents> {
        val written = model.fileArgument ?: return failure(
            "does not name its file with a `${ModelFileValidator.FILE_FIELD} = \"...\"` string " +
                "literal, and a literal is the only form the build can read a model from before " +
                "anything is compiled",
        )
        return readFile(assetRoot, written)
    }

    /**
     * The contents of the model file [written], as a script names it relative to [assetRoot], or
     * a failure whose message completes "model `<id>` ...". An `.fbx` is read as the `.glb` it
     * converts to; one that does not convert fails with a [ConversionFailure].
     */
    internal fun readFile(assetRoot: Path, written: String): Result<Contents> {
        val path = ResFile.of(written)
        if (path.isMalformed) return failure("names `$written`, which is not a path inside the asset root")
        val file = assetRoot.resolve(path.value)
        if (!file.isRegularFile()) {
            val suggestion = DidYouMean.suggest(path.value, modelFilesUnder(assetRoot))
            return failure(
                "names `$path`, which is not a file under the asset root." +
                    (suggestion?.let { " Did you mean '$it'?" } ?: ""),
            )
        }
        // An `.fbx` is read as the `.glb` it converts to (issue #244); one that does not convert
        // is the converter's `UDEA0039`, the same defect the validator reports, and not this rule.
        val read = if (ModelSources.isConverted(path)) {
            val glb = FbxConverter.convert(assetRoot, path).getOrElse { reason ->
                return Result.failure(ConversionFailure("names `$path`, which ${reason.message}"))
            }
            GltfClips.read(glb).andThen { clips -> GltfNodes.readModel(glb).map { Contents(clips, it.nodes, it.extras) } }
        } else {
            GltfClips.read(file).andThen { clips -> GltfNodes.readModel(file).map { Contents(clips, it.nodes, it.extras) } }
        }
        return read.exceptionOrNull()?.let { reason -> failure("names `$path`, which ${reason.message}") } ?: read
    }

    /** [next] over a [Result] that already holds a value, keeping the first failure. */
    private inline fun <T, R> Result<T>.andThen(next: (T) -> Result<R>): Result<R> =
        fold(onSuccess = next, onFailure = { Result.failure(it) })

    /** A model whose `.fbx` did not convert: reported under [AssetValidationRules.MODEL_CONVERSION]. */
    private class ConversionFailure(message: String) : IllegalArgumentException(message)

    /** Every model file under [assetRoot] - glTF or FBX - `/`-separated and relative to it. */
    @OptIn(ExperimentalPathApi::class)
    private fun modelFilesUnder(assetRoot: Path): List<String> =
        assetRoot.walk()
            .filter { it.isRegularFile() && it.extension.lowercase() in ModelSources.EXTENSIONS }
            .map { it.relativeTo(assetRoot).toString().replace('\\', '/') }
            .sorted()
            .toList()

    private fun <T> failure(why: String): Result<T> = Result.failure(IllegalArgumentException(why))
}
