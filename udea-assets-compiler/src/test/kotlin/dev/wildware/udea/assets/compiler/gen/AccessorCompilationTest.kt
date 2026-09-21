package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.scan.Declaration
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.assets.compiler.transpile.TranspiledAssetLoader
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.SourceSpan
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated accessors are real Kotlin, and `GameAssets.blueprint.player` really is a
 * `Ref<Blueprint>`.
 *
 * ## Why a compiler and not a string match
 *
 * Issue #90's criterion says *"proven by a compile-tested fixture"*, and it is right to insist:
 * a generator can emit `Ref<Blueprint>` as text while importing the wrong `Blueprint`, or emit
 * a `reference("...")` call that does not resolve, and every string assertion in
 * `AccessorGeneratorTest` would still pass. The proof that the type is right is a fixture that
 * assigns the member to a `val` of the declared type and compiles - and a companion fixture
 * that assigns it to the *wrong* type and must not.
 */
class AccessorCompilationTest {

    private val packAssets: Path = TestPaths.repoRoot.resolve(
        "udea-assets-compiler/src/test/resources/packassets",
    )

    private val classpath: List<Path> = TestPaths.compilerClasspath.filter { it.exists() }

    /** Writes the generated accessors plus [fixture] into a scratch tree and compiles them. */
    private fun compileWith(name: String, fixture: String): List<String> {
        val declarations = UdeaDeclarationScanner(TestPaths.repoRoot, packAssets).use { it.scanTree() }
        return compileGenerated(name, AccessorGenerator.generate(declarations.declarations), fixture)
    }

    /** Writes [generatedFiles] plus [fixture] into a scratch tree and compiles them. */
    private fun compileGenerated(name: String, generatedFiles: List<GeneratedFile>, fixture: String): List<String> {
        val scratch = TestPaths.scratch(name)
        val sources = scratch.resolve("src")
        val written = generatedFiles.map { generated ->
            val file = sources.resolve(generated.path)
            file.parent.createDirectories()
            file.writeText(generated.text)
            file
        }
        val fixtureFile = sources.resolve("Fixture.kt")
        fixtureFile.writeText(fixture)

        val loader = TranspiledAssetLoader(sources, scratch.resolve("classes"), classpath, packAssets)
        // `listOf(fixtureFile)`, not `+ fixtureFile`: `java.nio.file.Path` implements
        // `Iterable<Path>` over its own name elements, so `List<Path> + Path` picks the
        // `plus(Iterable)` overload and appends `Users`, `shaun`, `Workspace`, ... instead of
        // the file. It compiles, and the compiler then reports every segment as a missing
        // source file.
        return loader.compile(written + listOf(fixtureFile))
            .filter { it.severity == Severity.Error }
            .map { it.message }
    }

    @Test
    fun `a fixture that types the member as Ref of Blueprint compiles`() {
        val errors = compileWith(
            "accessors-ok",
            """
            package fixture

            import dev.wildware.udea.assets.Blueprint
            import dev.wildware.udea.assets.GameConfig
            import dev.wildware.udea.assets.Ref
            import dev.wildware.udea.assets.Shader
            import dev.wildware.udea.assets.SpriteSheet
            import dev.wildware.udea.generated.GameAssets

            val player: Ref<Blueprint> = GameAssets.blueprint.player
            val sheet: Ref<SpriteSheet> = GameAssets.character.orcIdle
            val config: Ref<GameConfig> = GameAssets.root.config
            val id: String = GameAssets.blueprint.player.id.value
            // The shape the shader API takes. `udea-render` is not on this module's classpath -
            // it is above it in the module graph - so what is compiled here is the accessor and
            // its type; `ShaderFromAssetTest` and `ShaderAssetProof` are where it meets a shader.
            val tint: Ref<Shader> = GameAssets.shaders.tint
            """.trimIndent() + "\n",
        )

        assertEquals(emptyList(), errors, "the generated accessors did not compile")
    }

    /**
     * A model's generated `Nodes` compile, extras included, and are the runtime `ModelNode` a
     * `Model` read from a bundle holds (issue #271).
     *
     * Compiled rather than matched as text for the reason the class KDoc gives: a generator can
     * write `extras = ModelExtras(...)` in words that read right and do not resolve. The node
     * list is what `GltfNodes` reads out of the Blender-made `module.glb`, so the extras compiled
     * here are every shape Blender writes - text, float, int, bool, a vector and a group.
     */
    @Test
    fun `a model's generated nodes compile with their authored extras`() {
        val module = TestPaths.repoRoot.resolve("udea-assets-compiler/src/test/resources/models/module/module.glb")
        val declaration = Declaration("model", "models/module", "module", SPAN, "models/module/module.glb")
        val nodes = mapOf("models/module" to GltfNodes.read(module).getOrThrow())
        val errors = compileGenerated(
            "accessors-extras",
            AccessorGenerator.generate(listOf(declaration), emptyMap(), nodes),
            """
            package fixture

            import dev.wildware.udea.assets.ModelNode
            import dev.wildware.udea.generated.Module

            val body: ModelNode = Module.Nodes.module
            val mass: Float? = Module.Nodes.module.extras.float("mass")
            val size: String? = Module.Nodes.module.extras.text("module_size")
            val slots: Int? = Module.Nodes.module.extras.int("fitting.slots")
            val offset: List<Float>? = Module.Nodes.module.extras.floats("offset")
            val armoured: Boolean? = Module.Nodes.module.extras.bool("armoured")
            val accepts: String? = Module.Nodes.socket_top.extras.text("accepts")
            val all: List<ModelNode> = Module.Nodes.all
            """.trimIndent() + "\n",
        )

        assertEquals(emptyList(), errors, "the generated nodes did not compile")
    }

    /**
     * The negative half.
     *
     * Without it, a generator that emitted every member as `Ref<AssetData>` would pass the test
     * above - `Ref<Blueprint>` would be assignable from nothing, but so would every other
     * check, because nothing would have narrowed the type.
     */
    @Test
    fun `a fixture that types a blueprint member as a sprite sheet does not compile`() {
        val errors = compileWith(
            "accessors-wrong-type",
            """
            package fixture

            import dev.wildware.udea.assets.Ref
            import dev.wildware.udea.assets.SpriteSheet
            import dev.wildware.udea.generated.GameAssets

            val wrong: Ref<SpriteSheet> = GameAssets.blueprint.player
            """.trimIndent() + "\n",
        )

        assertTrue(errors.isNotEmpty(), "`Ref<Blueprint>` was accepted where a `Ref<SpriteSheet>` was declared")
        assertTrue(
            errors.any { "Blueprint" in it || "SpriteSheet" in it || "type mismatch" in it.lowercase() },
            "the failure should be a type mismatch; it was $errors",
        )
    }

    /**
     * A `.udea.kts` that names `GameAssets` fails to compile (issue #90's
     * `AccessorsNotOnScriptClasspathTest`).
     *
     * ## What this can and cannot prove
     *
     * The script classpath used here is this module's test runtime classpath, which does **not**
     * carry the generated accessors - they were written to a scratch directory that is on no
     * classpath at all. So the failure is genuine.
     *
     * What it does not prove is that the *Gradle wiring* keeps them off: that lives in
     * `udea-gradle`, which registers the generated directory as a `srcDir` of `main` only, and
     * that module is not this one's to write. This is the half that can be tested from here,
     * and the other half is named rather than implied.
     */
    @Test
    fun `a udea kts referring to GameAssets does not compile`() {
        val scratch = TestPaths.scratch("accessors-script")
        val assets = scratch.resolve("assets")
        assets.createDirectories()
        val script = assets.resolve("bad.udea.kts")
        script.writeText(
            "import dev.wildware.udea.generated.GameAssets\n\n" +
                "blueprint(name = \"x\", parent = GameAssets.blueprint.player)\n",
        )

        val result = AssetCompiler(
            repoRoot = scratch,
            assetRoot = assets,
            scriptClasspath = classpath,
            cacheDirectory = scratch.resolve("cache"),
        ).compile(listOf(script))

        val errors = result.diagnostics.filter { it.severity == Severity.Error }
        assertTrue(errors.isNotEmpty(), "the script compiled, so GameAssets is on the script classpath")
        assertTrue(
            errors.any { "generated" in it.message || "GameAssets" in it.message || "Unresolved" in it.message },
            "the failure should be an unresolved GameAssets; it was ${errors.map { it.message }}",
        )
    }

    private companion object {
        val SPAN = SourceSpan("models/models.udea.kts", 1, 1, 1, 6)
    }
}
