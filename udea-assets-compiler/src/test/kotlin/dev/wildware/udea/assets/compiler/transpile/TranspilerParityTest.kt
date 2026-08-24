package dev.wildware.udea.assets.compiler.transpile

import dev.wildware.udea.assets.compiler.AssetCompileResult
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.assets.compiler.AssetScope
import dev.wildware.udea.assets.compiler.Fixtures
import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.diagnostics.Severity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The escape hatch, proved (issue #87).
 *
 * The claim the spec's risk mitigation rests on is that a `.udea.kts` maps almost one-to-one
 * onto `fun build(scope: AssetScope)`, so a second front end is cheap. This test is what makes
 * that a measured claim rather than a hopeful one: the same corpus goes through both front
 * ends and the two graphs are compared by id, kind and field value.
 */
class TranspilerParityTest {

    /**
     * The compile classpath for transpiled sources, with every `kotlin-scripting-*` jar
     * removed.
     *
     * This is the acceptance criterion, expressed as the classpath itself rather than as an
     * assertion about one: if the emitted code needed anything from the scripting host, it
     * would not compile here. The escape hatch is worth nothing if it still depends on the
     * component it exists to escape.
     */
    private fun scriptingFreeClasspath(): List<Path> {
        val full = TestPaths.compilerClasspath
        val removed = full.filter { "kotlin-scripting" in it.fileName.toString() }
        assertTrue(
            removed.isNotEmpty(),
            "the test classpath carries no kotlin-scripting-* jar, so filtering it proves nothing",
        )
        return full - removed.toSet()
    }

    private fun transpiler(assetRoot: Path = Fixtures.assetRoot) =
        UdeaTranspiler(TestPaths.repoRoot, assetRoot)

    private fun runTranspiled(
        name: String,
        assetRoot: Path,
        scripts: List<Path>,
    ): Pair<AssetCompileResult, List<TranspileResult>> {
        val results = transpiler(assetRoot).transpileAll(scripts)
        assertEquals(
            emptyList(),
            results.flatMap { it.diagnostics },
            "no fixture should be unsupported by the transpiler",
        )
        val loader = TranspiledAssetLoader(
            sourceDirectory = TestPaths.scratch("$name-src"),
            outputDirectory = TestPaths.scratch("$name-classes"),
            compileClasspath = scriptingFreeClasspath(),
        )
        val sources = loader.write(results)
        val compileDiagnostics = loader.compile(sources)
        assertEquals(
            emptyList(),
            compileDiagnostics.filter { it.severity == Severity.Error },
            "the transpiled sources must compile with the ordinary Kotlin compiler",
        )
        return loader.load() to results
    }

    @Test
    fun `both front ends produce the same graph`() {
        val scripts = Fixtures.scripts()

        val scriptStart = TimeSource.Monotonic.markNow()
        val viaScript = AssetCompiler(
            TestPaths.repoRoot,
            Fixtures.assetRoot,
            TestPaths.compilerClasspath,
            TestPaths.scratch("parity-script-cache"),
        ).compile(scripts)
        val scriptElapsed = scriptStart.elapsedNow()

        val transpiledStart = TimeSource.Monotonic.markNow()
        val (viaTranspiled, _) = runTranspiled("parity", Fixtures.assetRoot, scripts)
        val transpiledElapsed = transpiledStart.elapsedNow()

        println(
            "ScriptMode go/no-go over ${scripts.size} fixtures: " +
                "Script cold=$scriptElapsed, Transpiled cold=$transpiledElapsed",
        )

        assertEquals(Fixtures.EXPECTED_IDS, viaScript.graph.ids)
        assertEquals(Fixtures.EXPECTED_IDS, viaTranspiled.graph.ids)
        assertTrue(
            viaScript.graph.sameContentAs(viaTranspiled.graph),
            "the two front ends disagree:\n" + viaScript.graph.contentDiff(viaTranspiled.graph).joinToString("\n"),
        )

        // Kind and field values, spelled out rather than only compared, so a change that
        // degrades *both* front ends identically still fails.
        val orc = assertNotNull(viaTranspiled.graph.assets["character/orc"])
        assertEquals("character", orc.kind)
        assertEquals(500f, orc.fields["health"])
        val roles = (orc.fields["animationMap"] as Map<*, *>).entries
            .associate { (role, ref) -> role as String to (ref as Ref).id }
        assertEquals(mapOf("idle" to "character/orc_idle", "walk" to "character/orc_walk"), roles)
    }

    /**
     * The fixture that motivated keeping `.udea.kts` at all: a local helper function and a
     * `repeat(n)` loop, the shape of the real `level/test_level.udea.kts`.
     */
    @Test
    fun `a local helper and a repeat loop transpile to identical ids`() {
        val scripts = Fixtures.scripts()
        val (viaTranspiled, results) = runTranspiled("helper", Fixtures.assetRoot, scripts)

        val level = results.single { it.source.endsWith("level/test_level.udea.kts") }
        val code = assertNotNull(level.code)
        assertTrue("fun spawn(kind: String): Ref = scope.reference" in code, "the helper kept its shape:\n$code")
        assertTrue("repeat(3)" in code)
        assertTrue("override fun build(scope: AssetScope)" in code)
        assertEquals("dev.wildware.udea.assets.generated.LevelTestLevelAssets", level.className)

        assertEquals(
            setOf("level/test_level", "level/spawner_0", "level/spawner_1"),
            viaTranspiled.graph.ids.filter { it.startsWith("level/") }.toSet(),
        )
        val levelAsset = assertNotNull(viaTranspiled.graph.assets["level/test_level"])
        assertEquals(
            listOf("character/orc", "character/orc", "character/orc", "character/goblin"),
            (levelAsset.fields["entities"] as List<*>).map { (it as Ref).id },
            "the helper called through the loop three times and then once",
        )
    }

    /** Imports are hoisted and the emitted file is plain Kotlin. */
    @Test
    fun `the emitted source is plain Kotlin with hoisted imports`(@TempDir root: Path) {
        val assets = root.resolve("assets/character")
        assets.createDirectories()
        assets.resolve("orc.udea.kts").writeText(
            """
            import kotlin.math.roundToInt

            spriteSheet(name = "orc", spritePath = "/a.png", columns = 3.4f.roundToInt())
            """.trimIndent(),
        )
        val result = UdeaTranspiler(root, root.resolve("assets")).transpile(assets.resolve("orc.udea.kts"))
        val code = assertNotNull(result.code)

        assertTrue("package dev.wildware.udea.assets.generated" in code)
        assertTrue("import kotlin.math.roundToInt" in code, "the script's own import was hoisted:\n$code")
        assertTrue("import dev.wildware.udea.assets.compiler.AssetSource" in code)
        assertTrue("scope.spriteSheet(" in code, "the implicit-receiver call was qualified:\n$code")
        assertTrue("kotlin.script" !in code, "nothing about scripting may appear in the output")
        assertEquals("character", Regex("""idPrefix: String = "(.*)"""").find(code)?.groupValues?.get(1))
    }

    /**
     * A call that merely shares a name with an `AssetScope` member is left alone.
     *
     * Without the check, `builder.character(...)` would become `builder.scope.character(...)`
     * — which is the exact class of subtly-wrong output the transpiler is supposed to refuse
     * to produce.
     */
    @Test
    fun `an already-qualified call is not re-qualified`(@TempDir root: Path) {
        val assets = root.resolve("assets")
        assets.createDirectories()
        val file = assets.resolve("thing.udea.kts")
        file.writeText(
            """
            val other = StringBuilder()
            other.reference("not ours")
            spriteSheet(name = "thing", spritePath = "/a.png")
            """.trimIndent(),
        )
        val code = assertNotNull(UdeaTranspiler(root, assets).transpile(file).code)
        assertTrue("other.reference(\"not ours\")" in code, "a qualified call was rewritten:\n$code")
        assertTrue("scope.spriteSheet(" in code)
        assertTrue("other.scope." !in code)
    }

    /**
     * An unsupported construct is reported with a span and produces no code.
     *
     * Emitting *something* here is the failure mode worth avoiding: a named `object` cannot
     * live inside a function, so the alternative to refusing is emitting code that does not
     * compile — or worse, silently dropping the declaration.
     */
    @Test
    fun `an object declaration is reported as unsupported, with a span`(@TempDir root: Path) {
        val assets = root.resolve("assets")
        assets.createDirectories()
        val file = assets.resolve("thing.udea.kts")
        file.writeText(
            """
            spriteSheet(name = "thing", spritePath = "/a.png")

            object Registry {
                const val VERSION = 1
            }
            """.trimIndent(),
        )

        val result = UdeaTranspiler(root, assets).transpile(file)
        assertNull(result.code, "an unsupported script must emit nothing at all")
        val diagnostic = result.diagnostics.single()
        assertEquals(AssetCompilerRules.TRANSPILE_UNSUPPORTED.id, diagnostic.ruleId)
        val span = assertNotNull(diagnostic.span)
        assertEquals("assets/thing.udea.kts", span.path)
        assertEquals(3, span.startLine)
        assertTrue("object" in diagnostic.message)
    }

    @Test
    fun `a file annotation and a bare scope property read are unsupported`(@TempDir root: Path) {
        val assets = root.resolve("assets")
        assets.createDirectories()

        val annotated = assets.resolve("annotated.udea.kts")
        annotated.writeText(
            """
            @file:Suppress("unused")

            spriteSheet(name = "a", spritePath = "/a.png")
            """.trimIndent(),
        )
        assertTrue(
            UdeaTranspiler(root, assets).transpile(annotated).diagnostics.any { "@file:" in it.message },
        )

        val reads = assets.resolve("reads.udea.kts")
        reads.writeText(
            """
            spriteSheet(name = "a", spritePath = "/a.png")
            println(assets.size)
            """.trimIndent(),
        )
        val diagnostic = UdeaTranspiler(root, assets).transpile(reads).diagnostics.single()
        assertEquals(AssetCompilerRules.TRANSPILE_UNSUPPORTED.id, diagnostic.ruleId)
        assertTrue("AssetScope.assets" in diagnostic.message)
    }

    /** Two scripts reducing to one class name is reported, not silently overwritten. */
    @Test
    fun `a class-name collision across the batch is reported`(@TempDir root: Path) {
        val assets = root.resolve("assets")
        assets.resolve("a_b").createDirectories()
        assets.resolve("a").createDirectories()
        assets.resolve("a_b/c.udea.kts").writeText("""spriteSheet(name = "x", spritePath = "/a.png")""")
        assets.resolve("a/b_c.udea.kts").writeText("""spriteSheet(name = "y", spritePath = "/a.png")""")

        val results = UdeaTranspiler(root, assets).transpileAll(AssetCompiler.scriptsUnder(assets))
        assertEquals(2, results.size)
        assertTrue(results.all { it.code == null })
        assertTrue(results.all { r -> r.diagnostics.any { "transpile to the class" in it.message } })
    }

    /** The vocabulary the transpiler qualifies against is the real one. */
    @Test
    fun `AssetScope MEMBER_NAMES matches the class`() {
        val declared = AssetScope::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }
            // Synthetics, not API: `$default` bridges for default arguments, `access$...`
            // accessors the compiler generates for companion state, and property getters.
            .filterNot { "$" in it || it.startsWith("get") }
            .filterNot { it in setOf("equals", "hashCode", "toString") }
            .toSet()
        assertEquals(
            declared,
            AssetScope.MEMBER_NAMES,
            "AssetScope grew or lost a function and UdeaTranspiler's vocabulary did not follow",
        )
    }

    /** Regression guard on the generated file itself, so a change to it is visible in review. */
    @Test
    fun `the generated source for the sounds fixture is what we think it is`() {
        val results = transpiler().transpileAll(Fixtures.scripts())
        val sounds = results.single { it.source.endsWith("sounds/sounds.udea.kts") }
        val code = assertNotNull(sounds.code)
        assertTrue("""listOf("hit", "swoosh").forEach { kind ->""" in code, code)
        assertTrue("scope.soundCue(" in code, code)
        assertTrue("""override val idPrefix: String = "sounds"""" in code, code)
    }
}
