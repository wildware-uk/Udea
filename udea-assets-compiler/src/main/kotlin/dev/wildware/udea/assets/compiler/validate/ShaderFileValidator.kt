package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.Shader
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.shader.ShaderSources
import dev.wildware.udea.diagnostics.DidYouMean
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRule
import dev.wildware.udea.diagnostics.UdeaRules
import kotlin.io.path.isRegularFile

/**
 * A `shader(...)` whose `.frag` the build cannot use as a screen shader body: `UDEA0041`.
 *
 * ### Why the whole check is here and not at registration
 *
 * `UdeaShader.fragment` makes the last two of these checks itself, on the machine the game is
 * running on, because a game may build a shader out of GLSL it generated and there is no file to
 * have looked at. A shader declared as an asset is different: it is a file, compiled into the
 * `.udeapak` long before anything registers it, so a check left to registration is a check that
 * ships a pack nobody can use and fails on a player's machine. Both sides state the same two
 * rules because the rules are about GLSL, not about where the GLSL came from.
 *
 * ### Where the text comes from
 *
 * From the declaration's own `source` field, which
 * [dev.wildware.udea.assets.compiler.shader.ShaderSources] filled in pass 2 from the one read of
 * the file - not from a second read here. Two reads of a file somebody may be editing can see two
 * different files, and then the diagnostic describes one of them while the pack holds the other.
 * That is the same argument `ModelFileSource` makes for taking a model's clips and its nodes off
 * one visit.
 *
 * ### What is checked, in order
 *
 * 1. The declaration names a `file`, and the path stays inside the asset root.
 * 2. It is a `.${"frag"}` - [Shader.EXTENSION].
 * 3. The file exists. Reported with the did-you-mean spec section 5 makes mandatory, over the
 *    `.frag` files that *are* under the asset root - which is a narrower and more useful pool
 *    than [MissingFileValidator]'s `UDEA0032`, which suggests over every file of every kind.
 * 4. It could be read, and is not empty. A file that is present and unreadable is reported as
 *    what the operating system said, from what `ShaderSources` carried out of the failed read.
 * 5. It contains no `#version` at all, which the engine writes per backend. **Comments are not
 *    exempt** - see [Shader.VERSION_PRAGMA] for why, and `versionProblem` for how the message
 *    says so, since an author whose comment tripped it will otherwise read the diagnostic as
 *    simply wrong about their file.
 * 6. It mentions `udeaMain`, the one function a screen shader body defines.
 *
 * The first thing wrong is reported and the rest is not checked: each step assumes the one before
 * it held. A missing file is deliberately **both** this and `UDEA0032` - the two say different
 * useful things, and suppressing one would leave whichever an author has configured off.
 */
public object ShaderFileValidator : AssetValidator {

    override val rules: List<UdeaRule> = listOf(UdeaRules.SHADER_SOURCE)

    override fun validate(context: ValidationContext): List<UdeaDiagnostic> =
        context.graph.assets.values
            .filter { it.kind == ShaderSources.KIND }
            .sortedBy { it.id }
            .mapNotNull { shader ->
                val problem = problemWith(context, shader) ?: return@mapNotNull null
                UdeaRules.SHADER_SOURCE.diagnostic(
                    message = "shader `${shader.id}` $problem",
                    span = context.spanFor(shader),
                    assetId = shader.id,
                )
            }

    /** What is wrong with [shader], as a message completing "shader `id` ...", or `null`. */
    private fun problemWith(context: ValidationContext, shader: DeclaredAsset): String? {
        val path = ShaderSources.fileOf(shader)
            ?: return "does not name a `${ShaderSources.FILE_FIELD}`, so there is no GLSL to read"
        if (path.isMalformed) return "names `$path`, which is not a path inside the asset root"
        if (!ShaderSources.isShaderFile(path)) {
            return "names `$path`, which is not a .${Shader.EXTENSION} file"
        }
        if (!context.fileOf(path).isRegularFile()) {
            val suggestion = DidYouMean.suggest(path.value, shaderFilesIn(context))
            return "names `$path`, which is not a file under the asset root." +
                (suggestion?.let { " Did you mean '$it'?" } ?: "")
        }
        // Absent, rather than empty: `ShaderSources.fill` sets this on every shader declaration
        // it sees, so the only way to be here is a graph that never went through pass 2's fill.
        // That is a defect in whatever assembled the graph, and it is said out loud rather than
        // skipped, because skipping would make every check above it pass for the wrong reason.
        val source = shader.fields[ShaderSources.SOURCE_FIELD] as? String
            ?: return "names `$path`, which the asset compiler never read. That is a defect in " +
                "the build tool rather than in the shader: a graph reaching pass 3 has been " +
                "through `ShaderSources.fill`, which sets `${ShaderSources.SOURCE_FIELD}` on " +
                "every shader it sees."
        // The file is there and the read of it failed anyway. Reported as what the operating
        // system said rather than as "is empty", which is what it would look like otherwise.
        (shader.fields[ShaderSources.FAILURE_FIELD] as? String)?.let { failure ->
            return "names `$path`, which is there but could not be read: $failure"
        }
        return problemWithSource(path, source)
    }

    /**
     * What is wrong with the GLSL, or `null`.
     *
     * Both patterns are [Shader]'s, not this file's: `udea-render` refuses a body for the same
     * two reasons at registration, and the two must not be able to disagree about what a screen
     * shader is. `Shader.VERSION_PRAGMA` says why it is not comment-aware.
     */
    private fun problemWithSource(path: ResFile, source: String): String? = when {
        source.isBlank() -> "names `$path`, which is empty"
        Shader.VERSION_PRAGMA.containsMatchIn(source) -> versionProblem(path, source)
        !source.contains(Shader.ENTRY_POINT) ->
            "names `$path`, which never mentions `${Shader.ENTRY_POINT}`. A screen shader is one " +
                "function - `vec4 ${Shader.ENTRY_POINT}(vec2 uv)` - and the engine writes the " +
                "`main` that calls it."
        else -> null
    }

    /**
     * The `#version` message, which names the line and says the check does not read comments.
     *
     * The line number is not decoration. [Shader.VERSION_PRAGMA] is deliberately blind to
     * comments, so this fires on a `.frag` whose *comment* mentions the pragma - and the first
     * `.frag` this engine shipped did exactly that, in a comment saying the engine writes the line
     * itself. "States its own `#version`" was false about that file, and a message an author can
     * read as false about their file is a message they go looking for a compiler bug behind.
     *
     * So: say where it is, and say that commenting it out is not the fix. `udea-render` refuses
     * the same body at registration on the same regex, so an author who commented the line and
     * got past a comment-aware build would meet it again at the first draw - which is the failure
     * this whole asset kind exists to move earlier.
     */
    private fun versionProblem(path: ResFile, source: String): String {
        val hit = Shader.VERSION_PRAGMA.find(source)
        val line = if (hit == null) 0 else source.take(hit.range.first).count { it == '\n' } + 1
        return "names `$path`, which states a `#version` of its own at line $line. The engine " +
            "writes that line per backend - OpenGL and OpenGL ES disagree about it and about the " +
            "precision qualifiers a fragment stage needs - so a shader that states its own works " +
            "on one and fails on the other. Delete line $line and write the body alone. " +
            "Commenting it out is not enough: this check does not read comments, deliberately, " +
            "and neither does the one `udea-render` applies when the shader is registered."
    }

    /** Every `.frag` under the asset root, off the walk [ValidationContext] already does. */
    private fun shaderFilesIn(context: ValidationContext): List<String> =
        context.resourceFiles.filter {
            it.substringAfterLast('.', missingDelimiterValue = "").lowercase() == Shader.EXTENSION
        }
}
