package dev.wildware.udea.assets.compiler

import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.diagnostics.Severity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Pass 2 (issue #86): the fixture corpus compiles and evaluates to a populated [AssetGraph].
 */
class AssetCompilerTest {

    private fun compiler(cache: Path) = AssetCompiler(
        repoRoot = TestPaths.repoRoot,
        assetRoot = Fixtures.assetRoot,
        scriptClasspath = TestPaths.compilerClasspath,
        cacheDirectory = cache,
    )

    @Test
    fun `the corpus compiles and evaluates in one invocation`() {
        val cache = TestPaths.scratch("corpus-cache")
        val result = compiler(cache).compile(Fixtures.scripts())

        assertEquals(emptyList(), result.diagnostics.filter { it.severity == Severity.Error })
        assertEquals(Fixtures.EXPECTED_IDS, result.graph.ids)

        // Field values survive evaluation, including a reference in a List and one in a Map.
        val orc = assertNotNull(result.graph.assets["character/orc"])
        assertEquals("character", orc.kind)
        assertEquals(0.3f, orc.fields["size"])
        assertEquals(500f, orc.fields["health"])
        assertEquals(
            listOf("character/orc_idle", "character/orc_walk", "character/orc_attack_cue", "character/orc_death_cue"),
            orc.referencedIds,
        )

        // The file constant `val scale = 0.02f` was evaluated, not guessed at.
        assertEquals(0.02f, assertNotNull(result.graph.assets["character/orc_idle_sheet"]).fields["scale"])

        // ResPath normalisation: the author writes "/sprites/...", the model holds no leading
        // slash. This is the two-keys-for-one-file bug issue #84 names.
        // A `ResFile` and not a `String`: pass 3's MissingFileValidator finds every path a
        // declaration holds by *type*, so that a kind added later cannot forget to register
        // its path fields in a table somewhere else. See `ResFile`.
        assertEquals(
            ResFile("sprites/orc/idle.png"),
            result.graph.assets["character/orc_idle_sheet"]?.fields?.get("spritePath"),
        )

        // The sanctioned forEach and the repeat(n) loop both produced their assets.
        assertEquals(
            listOf("sounds/melee_hit", "sounds/melee_swoosh"),
            result.graph.ids.filter { it.startsWith("sounds/") },
        )
        assertTrue("level/spawner_1" in result.graph.ids)
    }

    /**
     * The `bundle { }` failure mode is gone by construction.
     *
     * The old definition evaluated a script for its *result value* and failed with "script
     * evaluated and returned a non-Asset object" when that value was not what it wanted. Here
     * the file is the bundle: the receiver collects declarations and the script's value is
     * ignored, so a script whose last statement is an `Int` is simply a script.
     */
    @Test
    fun `a script whose last expression is not an asset is still a valid script`(@TempDir assets: Path) {
        val cache = TestPaths.scratch("last-expression-cache")
        val file = assets.resolve("thing.udea.kts")
        file.writeText(
            """
            spriteSheet(name = "thing", spritePath = "/a.png")
            42
            """.trimIndent(),
        )
        val result = AssetCompiler(assets, assets, TestPaths.compilerClasspath, cache).compile(listOf(file))
        assertEquals(emptyList(), result.diagnostics.filter { it.severity == Severity.Error })
        assertEquals(setOf("thing"), result.graph.ids)
    }

    /**
     * A syntax error is a located diagnostic, not a stack trace.
     *
     * The host this replaces answered one with
     * `error("Failed to compile ... ${'$'}{e.stackTraceToString()}")`, which is a build
     * failure an agent cannot act on: no rule id, no file, no line.
     */
    @Test
    fun `a syntax error becomes a diagnostic with a repo-relative span`(@TempDir root: Path) {
        val cache = TestPaths.scratch("syntax-error-cache")
        val assets = root.resolve("assets")
        assets.createDirectories()
        val file = assets.resolve("broken.udea.kts")
        file.writeText(
            """
            spriteSheet(
                name = "broken",
            """.trimIndent(),
        )

        val result = AssetCompiler(root, assets, TestPaths.compilerClasspath, cache).compile(listOf(file))

        val error = result.diagnostics.first { it.severity == Severity.Error }
        assertEquals(AssetCompilerRules.SCRIPT_COMPILATION_FAILED.id, error.ruleId)
        val span = assertNotNull(error.span)
        assertEquals("assets/broken.udea.kts", span.path, "the span must be repo-relative")
        assertTrue(span.startLine >= 1, "the compiler's line survived the mapping")
        assertTrue(result.graph.ids.isEmpty())
        assertTrue("stackTrace" !in error.message)
    }

    /** One broken script does not cost the corpus the other four. */
    @Test
    fun `a broken script does not take the rest of the corpus with it`(@TempDir root: Path) {
        val cache = TestPaths.scratch("broken-cache")
        val assets = root.resolve("assets")
        assets.createDirectories()
        assets.resolve("good.udea.kts").writeText("""spriteSheet(name = "good", spritePath = "/a.png")""")
        assets.resolve("bad.udea.kts").writeText("""spriteSheet(name = )""")

        val result = AssetCompiler(root, assets, TestPaths.compilerClasspath, cache).compile(
            AssetCompiler.scriptsUnder(assets),
        )
        assertEquals(setOf("good"), result.graph.ids)
        assertTrue(result.hasErrors)
    }

    /**
     * The warm path: a second invocation with unchanged inputs hits the compiled-script jar
     * cache and finishes the corpus in under a second.
     *
     * The cold number is printed rather than asserted — it is what the escape-hatch go/no-go
     * in issue #87 compares against, and pinning it would be pinning the speed of whichever
     * machine runs CI.
     */
    @Test
    fun `a warm invocation hits the script cache and is under one second`() {
        val cache = TestPaths.scratch("warm-cache")
        val scripts = Fixtures.scripts()

        val coldStart = TimeSource.Monotonic.markNow()
        val cold = compiler(cache).compile(scripts)
        val coldElapsed = coldStart.elapsedNow()
        assertEquals(0, cold.cacheHits, "nothing can be cached on the first run")

        val warmStart = TimeSource.Monotonic.markNow()
        val warm = compiler(cache).compile(scripts)
        val warmElapsed = warmStart.elapsedNow()

        println("AssetCompiler Script mode: cold=$coldElapsed warm=$warmElapsed for ${scripts.size} scripts")
        assertEquals(scripts.size, warm.cacheHits, "every script should have been served from cache")
        assertEquals(cold.graph, warm.graph, "a cached compile must produce the same graph")
        assertTrue(warmElapsed.inWholeMilliseconds < 1000, "warm invocation took $warmElapsed, budget is 1s")
    }

    /**
     * The cache key covers the classpath, not just the text.
     *
     * The host this replaces keyed on MD5 of the script text plus `notTransientData`, so a jar
     * compiled against one version of the game's classes was served against a later,
     * incompatible one — a stale hit that surfaced as a `NoSuchMethodError` at runtime.
     */
    @Test
    fun `a changed classpath is a cache miss even when the text is identical`(@TempDir root: Path) {
        val cache = TestPaths.scratch("classpath-cache")
        val assets = root.resolve("assets")
        assets.createDirectories()
        assets.resolve("a.udea.kts").writeText("""spriteSheet(name = "a", spritePath = "/a.png")""")
        val scripts = AssetCompiler.scriptsUnder(assets)

        val extra = root.resolve("extra")
        extra.createDirectories()

        AssetCompiler(root, assets, TestPaths.compilerClasspath, cache).compile(scripts)
        val sameClasspath = AssetCompiler(root, assets, TestPaths.compilerClasspath, cache).compile(scripts)
        assertEquals(1, sameClasspath.cacheHits)

        val widened = AssetCompiler(root, assets, TestPaths.compilerClasspath + extra, cache).compile(scripts)
        assertEquals(0, widened.cacheHits, "a widened classpath must not be served a stale jar")
        assertEquals(setOf("a"), widened.graph.ids)
        assertTrue(
            cache.listDirectoryEntries("*.jar").size >= 2,
            "the two classpaths must occupy two cache entries, not overwrite one",
        )
    }

    /**
     * Origin capture, and the pass-1 index as its guaranteed fallback (issue #86).
     *
     * With capture off, no reference has an origin from the runtime, and every one still gets
     * a span — from the syntactic scan. That is the property the whole fallback exists for:
     * a build never fails for want of a line number.
     */
    @Test
    fun `references get an origin from capture, and from the pass-1 index when capture is off`() {
        val cache = TestPaths.scratch("origin-cache")
        val scripts = Fixtures.scripts()
        val index = UdeaDeclarationScanner(TestPaths.repoRoot, Fixtures.assetRoot)
            .use { it.scanFiles(scripts) }
            .referenceSpanIndex()

        val withoutCapture = compiler(cache).compile(scripts, spanIndex = index, captureOrigins = false)
        val orcRefs = assertNotNull(withoutCapture.graph.assets["character/orc"]).fields["animationMap"]
        @Suppress("UNCHECKED_CAST")
        val refs = (orcRefs as Map<String, Ref>).values.toList()
        assertTrue(refs.all { it.origin != null }, "pass 1's index must have located every reference")
        assertEquals(
            "udea-assets-compiler/src/test/resources/assets/character/orc.udea.kts",
            refs.first().origin?.path,
        )

        val withCapture = compiler(cache).compile(scripts, spanIndex = null, captureOrigins = true)
        @Suppress("UNCHECKED_CAST")
        val captured = (assertNotNull(withCapture.graph.assets["character/orc"])
            .fields["animationMap"] as Map<String, Ref>).values.toList()
        assertTrue(
            captured.all { it.origin != null },
            "origin capture must locate a reference written directly in a script",
        )
        val capturedLines: List<Int> = captured.mapNotNull { it.origin?.startLine }
        assertEquals(capturedLines.sorted(), capturedLines, "captured lines should be in source order")
    }

    /** Capture off and no index: no origin, and still no failure. */
    @Test
    fun `a reference with no origin is not an error`() {
        val cache = TestPaths.scratch("no-origin-cache")
        val result = compiler(cache).compile(Fixtures.scripts())
        @Suppress("UNCHECKED_CAST")
        val refs = (assertNotNull(result.graph.assets["character/orc"])
            .fields["animationMap"] as Map<String, Ref>).values.toList()
        assertTrue(refs.all { it.origin == null })
        assertEquals(emptyList(), result.diagnostics.filter { it.severity == Severity.Error })
    }
}
