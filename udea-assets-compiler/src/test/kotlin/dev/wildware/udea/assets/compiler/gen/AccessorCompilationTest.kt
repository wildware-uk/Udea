package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.assets.compiler.transpile.TranspiledAssetLoader
import dev.wildware.udea.diagnostics.Severity
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
        val scratch = TestPaths.scratch(name)
        val sources = scratch.resolve("src")
        val declarations = UdeaDeclarationScanner(TestPaths.repoRoot, packAssets).use { it.scanTree() }
        val written = AccessorGenerator.generate(declarations.declarations).map { generated ->
            val file = sources.resolve(generated.path)
            file.parent.createDirectories()
            file.writeText(generated.text)
            file
        }
        val fixtureFile = sources.resolve("Fixture.kt")
        fixtureFile.writeText(fixture)

        val loader = TranspiledAssetLoader(sources, scratch.resolve("classes"), classpath)
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
            import dev.wildware.udea.assets.SpriteSheet
            import dev.wildware.udea.generated.GameAssets

            val player: Ref<Blueprint> = GameAssets.blueprint.player
            val sheet: Ref<SpriteSheet> = GameAssets.character.orcIdle
            val config: Ref<GameConfig> = GameAssets.root.config
            val id: String = GameAssets.blueprint.player.id.value
            """.trimIndent() + "\n",
        )

        assertEquals(emptyList(), errors, "the generated accessors did not compile")
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
}
