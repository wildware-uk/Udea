package dev.wildware.udea.assets.compiler

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nothing is written outside the supplied cache directory (issue #86).
 *
 * The host this replaces cached compiled scripts at `File("./scripts/cache", ...)` — the
 * *process working directory*. Three consequences, all of which this test closes:
 *
 * - the cache was unshared: a Gradle build, an IDE run and a `java -jar` from a different
 *   directory each grew their own copy;
 * - it was never cleaned, because no build system knew it existed;
 * - it landed in whatever directory the game happened to be launched from, which for a
 *   packaged game is a directory the user did not agree to have written to.
 *
 * The cache directory is now an argument. This test asserts the working directory is
 * untouched, and specifically that no `./scripts/cache` appears.
 */
class NoStrayWritesTest {

    private val workingDirectory: Path = Path(System.getProperty("user.dir"))

    /**
     * Entries directly under the working directory, ignoring `build`.
     *
     * `build` is excluded because the Gradle test task legitimately writes there throughout a
     * run — including this test's own scratch cache. Everything else appearing is a stray
     * write.
     */
    private fun snapshot(): Set<String> =
        workingDirectory.listDirectoryEntries()
            .map { it.fileName.toString() }
            .filterNot { it == "build" || it == ".gradle" }
            .toSortedSet()

    @Test
    fun `compiling writes nothing into the working directory`() {
        val cache = TestPaths.scratch("no-stray-writes-cache")
        val before = snapshot()

        val result = AssetCompiler(
            repoRoot = TestPaths.repoRoot,
            assetRoot = Fixtures.assetRoot,
            scriptClasspath = TestPaths.compilerClasspath,
            cacheDirectory = cache,
        ).compile(Fixtures.scripts())

        assertEquals(Fixtures.EXPECTED_IDS, result.graph.ids, "sanity: the compile actually ran")
        assertEquals(before, snapshot(), "the compile created something in the working directory")

        // The specific ghost this replaces.
        assertTrue(
            !workingDirectory.resolve("scripts").exists(),
            "./scripts appeared in the working directory - the old CompiledScriptJarsCache is back",
        )
        assertTrue(!Path("scripts/cache").exists())

        // ...and the cache the caller asked for is where the jars actually went.
        assertTrue(cache.isDirectory())
        assertEquals(
            Fixtures.scripts().size,
            cache.listDirectoryEntries("*.jar").size,
            "one cached jar per script, in the supplied directory",
        )
    }

    /** The same property for the forked worker, whose working directory is also supplied. */
    @Test
    fun `the worker writes nothing into the caller's working directory`() {
        val cache = TestPaths.scratch("no-stray-writes-worker-cache")
        val work = TestPaths.scratch("no-stray-writes-worker-work")
        val before = snapshot()

        val result = dev.wildware.udea.assets.compiler.worker.IsolatedAssetCompiler(
            repoRoot = TestPaths.repoRoot,
            assetRoot = Fixtures.assetRoot,
            scriptClasspath = TestPaths.compilerClasspath,
            cacheDirectory = cache,
            workDirectory = work,
        ).compile(Fixtures.scripts())

        assertEquals(Fixtures.EXPECTED_IDS, result.graph.ids)
        assertEquals(before, snapshot())
        assertTrue(!workingDirectory.resolve("scripts").exists())
        // Request and response files are deleted on a successful call, so the work directory
        // does not accumulate one pair per compile in a long-lived daemon.
        assertEquals(emptyList(), work.listDirectoryEntries().map { it.fileName.toString() })
    }
}
