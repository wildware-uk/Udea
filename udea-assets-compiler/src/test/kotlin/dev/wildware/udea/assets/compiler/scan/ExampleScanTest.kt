package dev.wildware.udea.assets.compiler.scan

import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Pass 1 against the real example tree (issue #85).
 *
 * The corpus is `example/src/main/resources/assets`: the nineteen `.udea.kts` files issue #93
 * migrates. Real files rather than fixtures on purpose — a scanner tested only against shapes
 * its author thought of is a scanner that discovers the tree on migration day. It found two
 * things on first contact that no fixture would have: the whole corpus is CRLF (see
 * [UdeaDeclarationScanner.normalizeLineEndings]), and `character/wizard` references an ability
 * nothing declares.
 */
class ExampleScanTest {

    private fun scanner(repoRoot: Path = TestPaths.repoRoot, assets: Path = TestPaths.exampleAssets) =
        UdeaDeclarationScanner(repoRoot, assets)

    /**
     * The committed golden, refusing a copy a checkout has translated.
     *
     * Read through [GoldenResource] rather than straight off the classpath because the two
     * tests that compare against it compare *bytes*, and a carriage return renders as nothing:
     * on a `core.autocrlf=true` checkout the equality failed with both halves of the diff
     * printing identically. `udea-assets-compiler/.gitattributes` stops the translation and
     * this stops a translated copy arriving by some other route. Issue #176.
     */
    private fun goldenText(): String = GoldenResource.read(GoldenResource.EXAMPLE_DECLARATIONS)

    @Test
    fun `the scan of the example tree matches the golden`() {
        scanner().use { scanner ->
            val actual = DeclarationsJson.write(scanner.scanTree())
            if (actual != goldenText()) {
                // Written out so a legitimate change to the example tree is a one-command
                // update rather than a hand transcription of 160 lines.
                val rejected = TestPaths.repoRoot
                    .resolve("udea-assets-compiler/build/tmp/example-declarations.actual.json")
                rejected.parent.createDirectories()
                rejected.writeText(actual)
                assertEquals(goldenText(), actual, "scan differs from golden; actual written to $rejected")
            }
        }
    }

    @Test
    fun `every declaration id and its span is what the source says`() {
        scanner().use { scanner ->
            val report = scanner.scanTree()

            assertEquals(19, report.files.size, "the example tree has 19 .udea.kts files")
            assertEquals(83, report.declarations.size)
            assertEquals(83, report.ids.size, "no two declarations may collide on one id")

            // orc_elite.udea.kts is the richest file in the tree: one character, one animation
            // set, seven sprite sheets and two sound cues, all inside a `bundle { }`.
            val orcElite = report.files.single { it.path.endsWith("character/orc_elite.udea.kts") }
            assertEquals(
                listOf(
                    "character/orc_elite",
                    "character/orc_elite_animation_set",
                    "character/orc_elite_idle",
                    "character/orc_elite_walk",
                    "character/orc_elite_attack",
                    "character/orc_elite_attack_2",
                    "character/orc_elite_attack_3",
                    "character/orc_elite_hit",
                    "character/orc_elite_death",
                    "character/orc_elite_swoosh_sound_cue",
                    "character/orc_elite_big_shout_cue",
                ),
                orcElite.declarations.map { it.id },
            )

            // The id comes from the asset root and the name literal, never from a path
            // substring: `character` is the directory relative to the asset root.
            val character = orcElite.declarations.first()
            assertEquals("character", character.kind)
            assertEquals("orc_elite", character.name)
            assertEquals(14, character.span.startLine)
            assertEquals(5, character.span.startColumn)
            assertEquals("example/src/main/resources/assets/character/orc_elite.udea.kts", character.span.path)

            // A file at the asset root gets a bare id, and a kind with no `name` argument is
            // named for its file.
            assertEquals(setOf("config"), report.files.single { it.path.endsWith("/config.udea.kts") }.ids())
            assertEquals(setOf("level/test_level"), report.files.single { it.path.endsWith("test_level.udea.kts") }.ids())
        }
    }

    @Test
    fun `every reference literal gets a span and is attributed to its declaration`() {
        scanner().use { scanner ->
            val report = scanner.scanTree()
            assertEquals(79, report.references.size)
            assertTrue(report.references.all { it.from != null }, "every reference sits inside a declaration")

            val index = report.referenceSpanIndex()
            val site = index.sitesFor("ability/orc_elite_spin").single()
            assertEquals("character/orc_elite", site.from)
            assertEquals(48, site.span.startLine)
            assertNotNull(index.spanFor("character/orc_attack_cue"))

            // The one genuinely dangling reference in the tree. Pass 3 raises
            // UdeaRules.UNRESOLVED_REFERENCE on it; pass 1's job is to have a span ready.
            val dangling = report.references.map { it.target }.toSet() - report.ids
            assertEquals(setOf("ability/wizard_heal"), dangling)
            assertEquals(
                "example/src/main/resources/assets/character/wizard.udea.kts",
                index.spanFor("ability/wizard_heal")?.path,
            )
        }
    }

    @Test
    fun `declarations json holds no absolute path`() {
        scanner().use { scanner ->
            val json = DeclarationsJson.write(scanner.scanTree())
            val root = TestPaths.repoRoot.toString()
            assertTrue(root !in json, "the checkout directory leaked into declarations.json")
            assertTrue(root.replace('\\', '/') !in json)
            assertTrue(Regex("""[A-Za-z]:[/\\]""").find(json) == null, "a Windows drive path leaked")
            assertTrue(""""file": "/""" !in json, "a POSIX absolute path leaked")
        }
    }

    /**
     * The property that makes `declarations.json` usable as a build input: two developers
     * with different directory layouts get the same bytes.
     *
     * The two roots are deliberately different *lengths* as well as different names, because
     * the substring arithmetic this replaces (`substringAfterLast("assets/")`) fails on
     * exactly that difference.
     */
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `two checkouts produce byte-identical json`(@TempDir tmp: Path) {
        val relative = "example/src/main/resources/assets"
        val outputs = listOf("a", "a-much-longer-checkout-directory-name").map { name ->
            val checkout = tmp.resolve(name)
            val assets = checkout.resolve(relative)
            assets.createDirectories()
            TestPaths.exampleAssets.copyToRecursively(assets, followLinks = false, overwrite = true)
            UdeaDeclarationScanner(checkout, assets).use { DeclarationsJson.write(it.scanTree()) }
        }
        assertEquals(outputs[0], outputs[1])
        assertEquals(goldenText(), outputs[0], "a relocated checkout must still match the golden")
    }

    /**
     * The warm-scan budget from issue #85.
     *
     * Measured over a scanner whose PSI environment is already built, because that is the
     * daemon's steady state: the environment is created once and reused across rescans. A
     * cold measurement here would be measuring `KotlinCoreEnvironment`'s constructor, which
     * no rescan pays for.
     */
    @Test
    fun `a warm scan of the whole tree is under 200ms`() {
        scanner().use { scanner ->
            scanner.scanTree() // warm the PSI environment and the JIT
            scanner.clearCache()
            val start = TimeSource.Monotonic.markNow()
            val report = scanner.scanTree()
            val elapsed = start.elapsedNow()
            assertEquals(19, report.files.size)
            assertTrue(elapsed < 200.milliseconds, "warm scan took $elapsed, budget is 200ms")
        }
    }

    /** The per-file cache: a rescan of unchanged bytes reuses every result. */
    @Test
    fun `a rescan of unchanged files is answered from the cache`() {
        scanner().use { scanner ->
            val first = scanner.scanTree()
            assertEquals(0, scanner.cacheHits)
            val second = scanner.scanTree()
            assertEquals(19, scanner.cacheHits, "every unchanged file should have been cached")
            assertEquals(first, second)
        }
    }

    @Test
    fun `an edited file is rescanned and the rest are not`(@TempDir tmp: Path) {
        val assets = tmp.resolve("assets/character")
        assets.createDirectories()
        val edited = assets.resolve("orc.udea.kts")
        val untouched = assets.resolve("goblin.udea.kts")
        edited.writeText("""character(name = "orc")""")
        untouched.writeText("""character(name = "goblin")""")

        UdeaDeclarationScanner(tmp, tmp.resolve("assets")).use { scanner ->
            assertEquals(setOf("character/orc", "character/goblin"), scanner.scanTree().ids)
            edited.writeText("""character(name = "orc_reborn")""")
            val after = scanner.scanTree()
            assertEquals(setOf("character/orc_reborn", "character/goblin"), after.ids)
            assertEquals(1, scanner.cacheHits, "only the untouched file should be a cache hit")
        }
    }

    private fun FileScan.ids(): Set<String> = declarations.map { it.id }.toSet()

}
