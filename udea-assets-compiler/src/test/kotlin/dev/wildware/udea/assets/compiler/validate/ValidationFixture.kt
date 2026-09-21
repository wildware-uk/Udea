package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.compiler.AssetCompileResult
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.scan.ScanReport
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.diagnostics.Severity
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * A real asset tree on disk, compiled by the real pass 2, validated by the real pass 3.
 *
 * Nothing here builds a [dev.wildware.udea.assets.compiler.DeclaredAsset] by hand. The load-
 * bearing part of pass 3 is the metadata the *DSL signature* stamps — `Ref.expected` and
 * `ResFile` — and a graph assembled in a test would carry whatever the test author remembered
 * to put in it, which is precisely the drift the stamping exists to prevent. So a fixture is a
 * `.udea.kts` file, compiled.
 *
 * ### The art is the fixture
 *
 * [withArt] copies the committed orc sheets out of `example-assets` into the
 * scratch asset root. They are genuine third-party art: `Orc-Idle.png` really is 600x100 and
 * really does hold six 100x100 frames, so a geometry assertion against it is an assertion about
 * an image and not about a number a test wrote down. `docs/art-assets.md` is the manifest that
 * says what those numbers should be.
 */
internal object ValidationFixture {

    /**
     * Built fixtures, keyed by name.
     *
     * Compiling a `.udea.kts` costs seconds, and four assertions about one corpus are four
     * assertions about one corpus - not four corpora. Keyed by name because `TestPaths.scratch`
     * empties the directory it hands back, so re-building a fixture under a name already in use
     * would delete the tree an earlier context is still reading files from.
     */
    private val built = java.util.concurrent.ConcurrentHashMap<String, ValidationContext>()

    /**
     * A tree of scripts with no art copied in, and [prepare] run over the asset root first.
     *
     * [prepare] is how a fixture puts exact bytes on disk: every script here goes through
     * `trimIndent`, which splits on `\r\n` and `\r` as well as `\n` and joins with `\n`, so a
     * carriage return written as script text never reaches the file.
     */
    fun context(
        name: String,
        vararg scripts: Pair<String, String>,
        prepare: (assets: Path) -> Unit = {},
    ): ValidationContext =
        built.computeIfAbsent(name) { build(it, art = false, scripts = scripts, prepare = prepare) }

    /** A tree of scripts with the committed orc sprite sheets copied into `sprites/orc`. */
    fun withArt(name: String, vararg scripts: Pair<String, String>): ValidationContext =
        built.computeIfAbsent(name) { build(it, art = true, scripts = scripts) }

    /**
     * A tree of scripts with the committed Khronos Fox copied to `models/fox/Fox.glb`.
     *
     * The real file, not bytes a test wrote: a check that a model file is glTF 2.0 is then a
     * check against the file a game would ship (issue #240).
     */
    fun withFox(
        name: String,
        vararg scripts: Pair<String, String>,
        prepare: (assets: Path) -> Unit = {},
    ): ValidationContext =
        built.computeIfAbsent(name) { build(it, art = false, scripts = scripts, fox = true, prepare = prepare) }

    /**
     * A tree of scripts with the FBX test fixture - `Bender.fbx` and the `checker.png` it links -
     * copied to `models/bender/`, then [prepare] run over the asset root (issue #244).
     */
    @OptIn(ExperimentalPathApi::class)
    fun withFbx(
        name: String,
        vararg scripts: Pair<String, String>,
        prepare: (assets: Path) -> Unit = {},
    ): ValidationContext =
        built.computeIfAbsent(name) {
            build(it, art = false, scripts = scripts) { assets ->
                val target = assets.resolve("models").resolve("bender")
                target.createDirectories()
                TestPaths.repoRoot.resolve("udea-assets-compiler/src/test/resources/fbx/bender")
                    .copyToRecursively(target, followLinks = false, overwrite = true)
                prepare(assets)
            }
        }

    /** The pipeline's verdict on a fixture. */
    fun report(context: ValidationContext) = AssetValidatorPipeline().validate(context)

    @OptIn(ExperimentalPathApi::class)
    private fun build(
        name: String,
        art: Boolean,
        scripts: Array<out Pair<String, String>>,
        fox: Boolean = false,
        prepare: (assets: Path) -> Unit = {},
    ): ValidationContext {
        val scratch = TestPaths.scratch("validate/$name")
        val assets = scratch.resolve("assets")
        assets.createDirectories()

        if (art) {
            val source = TestPaths.exampleAssets.resolve("sprites").resolve("orc")
            val target = assets.resolve("sprites").resolve("orc")
            target.createDirectories()
            source.copyToRecursively(target, followLinks = false, overwrite = true)
        }

        if (fox) {
            val source = TestPaths.exampleAssets.resolve("models").resolve("fox")
            val target = assets.resolve("models").resolve("fox")
            target.createDirectories()
            source.copyToRecursively(target, followLinks = false, overwrite = true)
        }

        prepare(assets)

        for ((relative, text) in scripts) {
            val file = assets.resolve(relative)
            file.parent.createDirectories()
            file.writeText(text.trimIndent())
        }

        val scan = UdeaDeclarationScanner(TestPaths.repoRoot, assets).use { it.scanTree() }
        return ValidationContext.of(compile(scratch, assets, scan), TestPaths.repoRoot, assets, scan)
    }

    /**
     * Pass 1 then pass 2, exactly as a build runs them.
     *
     * The scan is not optional decoration: its [UdeaDeclarationScanner] reference span index is
     * what gives every `reference("...")` the span of its own string literal, and therefore what
     * lets `UnresolvedReferenceValidator` offer a `Fix` that replaces the literal. Compiling
     * without it would silently degrade every reference diagnostic to its declaration's line.
     */
    private fun compile(scratch: Path, assets: Path, spans: ScanReport): AssetCompileResult {
        val scripts = AssetCompiler.scriptsUnder(assets)
        val result = AssetCompiler(
            repoRoot = TestPaths.repoRoot,
            assetRoot = assets,
            scriptClasspath = TestPaths.compilerClasspath,
            cacheDirectory = scratch.resolve("cache"),
        ).compile(scripts, spans.referenceSpanIndex())

        // A fixture whose *scripts* do not compile would make every pass-3 assertion below it
        // vacuous - the graph would be empty and every validator would find nothing and pass.
        assertEquals(
            emptyList(),
            result.diagnostics.filter { it.severity == Severity.Error },
            "the fixture scripts must compile; pass 3 assertions on an empty graph prove nothing",
        )
        return result
    }
}
