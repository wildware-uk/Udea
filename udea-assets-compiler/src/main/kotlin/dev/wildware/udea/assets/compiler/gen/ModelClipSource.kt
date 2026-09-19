package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.scan.Declaration
import dev.wildware.udea.assets.compiler.validate.ModelFileValidator
import dev.wildware.udea.diagnostics.DidYouMean
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/** What [ModelClipSource] found: each model's clips by asset id, and what stopped the rest. */
internal class ModelClipScan(
    /** Asset id to that model's clips, in file order. A model that failed is absent. */
    val clips: Map<String, List<GltfClip>>,
    val diagnostics: List<UdeaDiagnostic>,
)

/**
 * The animation clips of every `model(...)` in a scan, read from the files they name (issue #241).
 *
 * This is the accessors pass's one look at the asset tree, and it looks at the model files and
 * nothing else: the rest of what it generates comes from the scan alone. It runs before any game
 * code compiles, so a model whose clips cannot be read is reported here, under
 * [AssetCompilerRules.MODEL_CLIPS], rather than left to surface as every `Fox.Clips.Run` failing
 * to resolve.
 */
internal object ModelClipSource {

    /** Reads the clips of every model among [declarations], resolving files against [assetRoot]. */
    fun read(assetRoot: Path, declarations: List<Declaration>): ModelClipScan {
        val clips = LinkedHashMap<String, List<GltfClip>>()
        val diagnostics = ArrayList<UdeaDiagnostic>()
        val models = declarations.filter { it.kind == ModelFileValidator.KIND }.distinctBy { it.id }.sortedBy { it.id }
        for (model in models) {
            readOne(assetRoot, model).fold(
                onSuccess = { clips[model.id] = it },
                onFailure = { problem ->
                    diagnostics += AssetCompilerRules.MODEL_CLIPS.diagnostic(
                        message = "model `${model.id}` ${problem.message}",
                        span = model.span,
                        assetId = model.id,
                    )
                },
            )
        }
        return ModelClipScan(clips, diagnostics)
    }

    /** One model's clips, or a failure whose message completes "model `<id>` ...". */
    private fun readOne(assetRoot: Path, model: Declaration): Result<List<GltfClip>> {
        val written = model.fileArgument ?: return failure(
            "does not name its file with a `${ModelFileValidator.FILE_FIELD} = \"...\"` string " +
                "literal, and a literal is the only form the build can read clips from before " +
                "anything is compiled",
        )
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
        val read = GltfClips.read(file)
        return read.exceptionOrNull()?.let { reason -> failure("names `$path`, which ${reason.message}") } ?: read
    }

    /** Every `.glb` and `.gltf` under [assetRoot], `/`-separated and relative to it. */
    @OptIn(ExperimentalPathApi::class)
    private fun modelFilesUnder(assetRoot: Path): List<String> =
        assetRoot.walk()
            .filter { it.isRegularFile() && it.extension.lowercase() in Model.EXTENSIONS }
            .map { it.relativeTo(assetRoot).toString().replace('\\', '/') }
            .sorted()
            .toList()

    private fun <T> failure(why: String): Result<T> = Result.failure(IllegalArgumentException(why))
}
