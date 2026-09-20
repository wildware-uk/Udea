package dev.wildware.udea.assets.compiler.shader

import dev.wildware.udea.assets.Shader
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.ResFile
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Reads the `.frag` behind every `shader(...)` and puts its text into the declaration.
 *
 * ## Why the text is copied into the asset at all
 *
 * Because the alternative does not exist. Every other file-backed asset names a file and leaves
 * reading it to a platform - `udea-render`'s `ModelFiles` is `jvmMain` and opens a `.glb` with
 * `java.nio` - and that is exactly the shape the owner rejected for shaders:
 * `javaClass.getResource("/shaders/scanlines.frag").readText()` is three JVM-only things in one
 * line, and a game following it has to write that line once per platform. Packing the GLSL as an
 * ordinary string field of an ordinary graph record means the text comes back out of
 * `BundleReader` in `commonMain`, on every target, through the reader every other asset already
 * uses. There is no shader loader, because there is nothing left for one to do.
 *
 * ## Why it happens in pass 2
 *
 * [fill] is called from `AssetCompiler.compile`, which is the one door both drivers of the five
 * passes go through: the Gradle pipeline and the dev daemon. Filling it in the pipeline alone
 * would leave the daemon holding shaders with no source and pushing a reload that blanked them.
 *
 * ## This reports nothing, and that is the split
 *
 * Every complaint about a shader file is [ShaderFileValidator]'s, in pass 3, under `UDEA0041`.
 * That is where a declaration's **span** is: `DeclaredAsset.origin` is filled only when the
 * daemon's origin capture is on, and pass 1's scan - the thing that actually knows which line
 * each declaration was written on - is not an input to pass 2. A diagnostic raised here would be
 * a diagnostic with no line number in every ordinary build.
 *
 * The validator does not read the file a second time either: it reads [SOURCE_FIELD], which is
 * what this put there. One read of the file, one opinion about what was in it.
 *
 * @see ShaderFileValidator
 */
internal object ShaderSources {

    /** The DSL word whose declarations are shaders. */
    const val KIND: String = "shader"

    /** The declaration field that names the `.frag`. */
    const val FILE_FIELD: String = "file"

    /**
     * The field this fills in: `GraphPacker` writes it and `Shader.source` reads it back.
     *
     * It is set on **every** shader declaration, including one whose file could not be read,
     * where it is the empty string. An absent key therefore means a graph that did not come
     * through [fill], which `ShaderFileValidator` reports as the build-tool defect it is rather
     * than passing over in silence.
     */
    const val SOURCE_FIELD: String = "source"

    /**
     * Why a file could not be read, when the operating system gave a reason.
     *
     * Build-time only: `GraphPacker`'s shader schema names `file` and [SOURCE_FIELD] and nothing
     * else, so this never reaches a `.udeapak`. It exists so that a file which is present but
     * unreadable - a permission, a device error - is reported as *that* rather than as "is
     * empty", which is what swallowing the exception here would have turned it into.
     */
    const val FAILURE_FIELD: String = "sourceFailure"

    /**
     * [declared], with every `shader(...)` carrying the GLSL its file holds, or `""`.
     *
     * A declaration that is not a shader is passed through as the same object, so this is a
     * no-op for a tree with no shaders in it - including, by construction, for the graph
     * equality `TranspilerParityTest` compares.
     */
    fun fill(assetRoot: Path, declared: List<DeclaredAsset>): List<DeclaredAsset> {
        if (declared.none { it.kind == KIND }) return declared
        return declared.map { asset ->
            if (asset.kind != KIND) return@map asset
            val read = read(assetRoot, asset)
            val filled = asset.fields + (SOURCE_FIELD to read.text)
            asset.copy(fields = if (read.failure == null) filled else filled + (FAILURE_FIELD to read.failure))
        }
    }

    /** The path a shader declaration names, or `null` when it names none this build can use. */
    fun fileOf(asset: DeclaredAsset): ResFile? = when (val declared = asset.fields[FILE_FIELD]) {
        is ResFile -> declared
        is String -> ResFile.of(declared)
        else -> null
    }

    /** Whether [path] is spelled as a shader file at all. */
    fun isShaderFile(path: ResFile): Boolean =
        path.value.substringAfterLast('.', missingDelimiterValue = "").lowercase() == Shader.EXTENSION

    /** What one file read produced: the text, and the reason there is none. */
    private class Read(val text: String, val failure: String?)

    /**
     * The text of [asset]'s file, or `""` with a reason for anything this cannot read.
     *
     * Never throws: a pass-2 exception costs the author every other asset in the same script.
     * The four ways to get nothing are told apart rather than lumped together - a path that
     * escapes the asset root, a file that is not a `.frag`, a file that is not there and a file
     * the operating system refuses - because the first three are pass 3's to describe from the
     * declaration alone and only the last one has a cause that is lost if it is not carried.
     */
    private fun read(assetRoot: Path, asset: DeclaredAsset): Read {
        val path = fileOf(asset) ?: return NOTHING
        if (path.isMalformed || !isShaderFile(path)) return NOTHING
        val file = assetRoot.resolve(path.value)
        if (!file.isRegularFile()) return NOTHING
        return try {
            Read(file.readText(), failure = null)
        } catch (failure: IOException) {
            Read("", "${failure::class.simpleName}: ${failure.message}")
        }
    }

    /** No text and no cause worth carrying: pass 3 can see all three of these for itself. */
    private val NOTHING = Read("", failure = null)
}
