package dev.wildware.udea.assets.compiler.model

import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.scan.Declaration
import dev.wildware.udea.assets.compiler.validate.AssetValidationRules
import dev.wildware.udea.assets.compiler.validate.ModelFileValidator
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * The `.glb` committed beside each `.fbx` model, and the two tasks that keep it true (the
 * follow-up to issue #244).
 *
 * ### Why the build does not convert
 *
 * LWJGL 3.3.6 ships two different native builds of Assimp: the Linux library reports
 * `assimp v5.4.0` and the Windows one `assimp v5.4.90d2a697`. They convert one `.fbx` to node
 * rotations, accessor bounds and animation samples that differ in the last bits of their floats,
 * and since #271 packs a model's node transforms into the asset graph, a Windows build packed a
 * different graph hash than Linux and every recorded replay stopped matching there. A player on
 * Windows was also drawing a very slightly different mesh.
 *
 * So Assimp runs once, on purpose, and its output is committed: `models/human/Human.fbx` has
 * `models/human/Human.glb` beside it, and every pass that reads the model - the validator, the
 * accessors, the pack - reads those committed bytes through [read]. Every platform then packs the
 * same file.
 *
 * - [write] is [WRITE_TASK]: it converts every `.fbx` a `model(...)` names and writes the result.
 * - [verify] is [VERIFY_TASK], on `check`: it converts again and fails when the result is not the
 *   committed file, which is what an `.fbx` edited without re-running the writer looks like. It
 *   runs only on [ConversionHost.isReference], the platform the committed files were made on,
 *   because anywhere else Assimp's answer differs whether or not anything is stale.
 *
 * A `.fbx` that does not convert fails both with `UDEA0039`, and so does a missing or stale
 * `.glb`: the rule is the one for "this model's conversion is wrong", and the message says which.
 */
internal object CommittedModels {

    /** The task that converts every `.fbx` model and writes its `.glb` into the asset tree. */
    const val WRITE_TASK: String = "udeaWriteConvertedModels"

    /** The task, on `check`, that fails when a committed `.glb` is not its `.fbx`'s conversion. */
    const val VERIFY_TASK: String = "udeaVerifyConvertedModels"

    /** What to tell a person whose committed `.glb` is missing or stale. */
    private val REMEDY: String =
        "run `./gradlew $WRITE_TASK` on ${ConversionHost.REFERENCE} and commit the .glb it writes"

    /**
     * The committed `.glb` for the `.fbx` model file [fbx], or a failure whose message completes
     * "model `<id>` names `<fbx>`, which ...".
     */
    fun read(assetRoot: Path, fbx: ResFile): Result<ByteArray> {
        val glb = ModelSources.runtimeFile(fbx)
        val file = assetRoot.resolve(glb.value)
        if (!file.isRegularFile()) {
            return failure("has no converted `$glb` committed beside it: $REMEDY")
        }
        return Result.success(file.readBytes())
    }

    /** One `model(...)` that names an `.fbx`: the declaration, and the file as it names it. */
    class FbxModel(val declaration: Declaration, val fbx: ResFile) {
        /** The `.glb` committed for it. */
        val glb: ResFile get() = ModelSources.runtimeFile(fbx)
    }

    /**
     * Every `model(...)` among [declarations] that names an `.fbx` that is there, in id order. A
     * file that is missing or malformed is left out: the validator already reports it, with its
     * did-you-mean, and one defect is not two diagnostics.
     */
    fun fbxModels(assetRoot: Path, declarations: List<Declaration>): List<FbxModel> =
        declarations
            .filter { it.kind == ModelFileValidator.KIND }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .mapNotNull { declaration ->
                val written = declaration.fileArgument ?: return@mapNotNull null
                val path = ResFile.of(written)
                if (path.isMalformed || !ModelSources.isConverted(path)) return@mapNotNull null
                if (!assetRoot.resolve(path.value).isRegularFile()) return@mapNotNull null
                FbxModel(declaration, path)
            }

    /** What [write] did: each `.glb` it wrote, and every model that did not convert. */
    class Written(val files: List<ResFile>, val diagnostics: List<UdeaDiagnostic>)

    /**
     * Converts every `.fbx` model among [declarations] and writes each `.glb` beside its `.fbx`,
     * under [assetRoot]. A model that does not convert is a `UDEA0039` and writes nothing, and the
     * `.glb` already there is left as it was.
     */
    fun write(assetRoot: Path, declarations: List<Declaration>): Written {
        val files = ArrayList<ResFile>()
        val diagnostics = ArrayList<UdeaDiagnostic>()
        for (model in fbxModels(assetRoot, declarations)) {
            FbxConverter.convert(assetRoot, model.fbx).fold(
                onSuccess = { bytes ->
                    val target = assetRoot.resolve(model.glb.value)
                    target.parent?.createDirectories()
                    target.writeBytes(bytes)
                    files += model.glb
                },
                onFailure = { reason -> diagnostics += conversion(model, "which ${reason.message}") },
            )
        }
        return Written(files, diagnostics)
    }

    /** What [verify] found. */
    sealed interface Verdict {
        /** Not checked here, and [reason] says why in words a build log can carry. */
        class Skipped(val reason: String) : Verdict

        /** Checked: [current] are the models whose committed `.glb` is their conversion. */
        class Checked(val current: List<ResFile>, val diagnostics: List<UdeaDiagnostic>) : Verdict
    }

    /**
     * Whether every `.fbx` model among [declarations] has, committed beside it, exactly the
     * `.glb` it converts to on [host] - or [Verdict.Skipped] when [host] is not the platform the
     * committed files are made on, where Assimp's answer differs whatever is committed.
     */
    fun verify(assetRoot: Path, declarations: List<Declaration>, host: ConversionHost): Verdict {
        if (!host.isReference) {
            return Verdict.Skipped(
                "skipped on $host: the committed .glb files are converted on ${ConversionHost.REFERENCE}, and " +
                    "Assimp's native build for this platform converts the same .fbx to floats that differ in " +
                    "their last bits, so only ${ConversionHost.REFERENCE} can say whether they are current",
            )
        }
        val current = ArrayList<ResFile>()
        val diagnostics = ArrayList<UdeaDiagnostic>()
        for (model in fbxModels(assetRoot, declarations)) {
            val converted = FbxConverter.convert(assetRoot, model.fbx).getOrElse { reason ->
                diagnostics += conversion(model, "which ${reason.message}")
                continue
            }
            val committed = read(assetRoot, model.fbx).getOrElse { reason ->
                diagnostics += conversion(model, "which ${reason.message}")
                continue
            }
            if (committed.contentEquals(converted)) {
                current += model.glb
            } else {
                diagnostics += conversion(
                    model,
                    "which no longer converts to the `${model.glb}` committed beside it (${converted.size} " +
                        "bytes converted, ${committed.size} committed), so the .fbx or a texture it names " +
                        "changed after the .glb was written: $REMEDY",
                )
            }
        }
        return Verdict.Checked(current, diagnostics)
    }

    private fun conversion(model: FbxModel, problem: String): UdeaDiagnostic =
        AssetValidationRules.MODEL_CONVERSION.diagnostic(
            message = "model `${model.declaration.id}` names `${model.fbx}`, $problem",
            span = model.declaration.span,
            assetId = model.declaration.id,
        )

    private fun <T> failure(why: String): Result<T> = Result.failure(IllegalArgumentException(why))
}

/**
 * The platform an asset build runs on, as the JVM names it: `os.name` and `os.arch`.
 *
 * Only [isReference] is asked of it. The committed `.glb` files are made on Linux x86_64 - the
 * platform CI's Linux jobs and the machine the first ones were written on share - and the check
 * runs there alone. Linux on another architecture is not the reference either: its Assimp is a
 * different native binary again, and nothing has shown that it agrees.
 */
internal data class ConversionHost(val osName: String, val osArch: String) {

    /** True when this is the platform whose Assimp the committed `.glb` files came from. */
    val isReference: Boolean
        get() = osName.startsWith("Linux") && osArch in X86_64

    override fun toString(): String = "$osName $osArch"

    companion object {
        /** The reference platform, as a person reads it. */
        const val REFERENCE: String = "Linux x86_64"

        /** The two names a JVM gives x86_64. */
        private val X86_64 = setOf("amd64", "x86_64")

        /** The platform this JVM is running on. */
        fun current(): ConversionHost =
            ConversionHost(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty())
    }
}
