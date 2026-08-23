package dev.wildware.udea.compiler.assets

import dev.wildware.udea.diagnostics.assets.AssetCatalog
import dev.wildware.udea.diagnostics.assets.AssetCatalogConflict
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AssetCatalogReaderTest {

    @Test
    fun `two fixture jars merge into one catalog`() {
        val upstream = AssetIndexFixtures.jarRoot(
            AssetIndexFixtures.encoded(
                AssetIndexFixtures.catalog(AssetIndexFixtures.ORC to AssetIndexFixtures.CHARACTER_KIND),
            ),
            name = "characters.jar",
        )
        val downstream = AssetIndexFixtures.jarRoot(
            AssetIndexFixtures.encoded(
                AssetIndexFixtures.catalog(AssetIndexFixtures.ARROW to AssetIndexFixtures.BLUEPRINT_KIND),
            ),
            name = "blueprints.jar",
        )

        val scan = ClasspathAssetCatalogScanner(listOf(upstream, downstream)).scan()

        assertEquals(listOf(AssetIndexFixtures.ARROW, AssetIndexFixtures.ORC), scan.catalog.ids)
        assertEquals(emptyList(), scan.problems)
    }

    @Test
    fun `a directory root is read the same way a jar is`() {
        val jar = AssetIndexFixtures.jarRoot(
            AssetIndexFixtures.encoded(
                AssetIndexFixtures.catalog(AssetIndexFixtures.ORC to AssetIndexFixtures.CHARACTER_KIND),
            ),
        )
        val directory = AssetIndexFixtures.directoryRoot(
            AssetIndexFixtures.encoded(
                AssetIndexFixtures.catalog(AssetIndexFixtures.ARROW to AssetIndexFixtures.BLUEPRINT_KIND),
            ),
        )

        val fromJar = ClasspathAssetCatalogScanner(listOf(jar)).scan()
        val fromDirectory = ClasspathAssetCatalogScanner(listOf(directory)).scan()
        val fromBoth = ClasspathAssetCatalogScanner(listOf(jar, directory)).scan()

        assertEquals(listOf(AssetIndexFixtures.ORC), fromJar.catalog.ids)
        assertEquals(listOf(AssetIndexFixtures.ARROW), fromDirectory.catalog.ids)
        assertEquals(listOf(AssetIndexFixtures.ARROW, AssetIndexFixtures.ORC), fromBoth.catalog.ids)
    }

    @Test
    fun `an id declared by two modules with different kinds is reported once`() {
        val text = AssetIndexFixtures.encoded(
            AssetIndexFixtures.catalog(AssetIndexFixtures.ORC to AssetIndexFixtures.CHARACTER_KIND),
        )
        val other = AssetIndexFixtures.encoded(
            AssetIndexFixtures.catalog(AssetIndexFixtures.ORC to AssetIndexFixtures.BLUEPRINT_KIND),
        )
        val roots = listOf(
            AssetIndexFixtures.jarRoot(text, "a.jar"),
            AssetIndexFixtures.jarRoot(other, "b.jar"),
            AssetIndexFixtures.jarRoot(text, "c.jar"),
        )

        val scan = ClasspathAssetCatalogScanner(roots).scan()

        assertEquals(
            listOf(
                AssetCatalogConflict(
                    AssetIndexFixtures.ORC,
                    listOf(AssetIndexFixtures.BLUEPRINT_KIND, AssetIndexFixtures.CHARACTER_KIND),
                ),
            ),
            scan.catalog.conflicts,
        )
        assertEquals(listOf(AssetIndexFixtures.ORC), scan.catalog.ids)
    }

    @Test
    fun `no index anywhere on the classpath is an empty catalog and zero problems`() {
        val scan = ClasspathAssetCatalogScanner(
            listOf(
                AssetIndexFixtures.emptyRoot(),
                File("does-not-exist-on-any-disk.jar"),
            ),
        ).scan()

        assertTrue(scan.isSilent, "an absent index must be silent, not an error")
        assertSame(AssetCatalog.EMPTY, scan.catalog)
        assertEquals(emptyList(), scan.problems)
    }

    @Test
    fun `an empty classpath is an empty catalog`() {
        assertTrue(ClasspathAssetCatalogScanner(emptyList()).scan().isSilent)
    }

    @Test
    fun `a bumped format version is one problem naming both versions`() {
        val scan = ClasspathAssetCatalogScanner(
            listOf(AssetIndexFixtures.versionedRoot(AssetCatalog.FORMAT_VERSION + 1)),
        ).scan()

        val problem = assertIs<AssetCatalogProblem.UnknownVersion>(scan.problems.single())
        assertEquals(AssetCatalog.FORMAT_VERSION + 1, problem.found)
        assertEquals(AssetCatalog.FORMAT_VERSION, problem.expected)
        assertEquals("future.jar", problem.origin, "the origin must be a name, never a path")
    }

    @Test
    fun `a malformed index is a problem, not an exception`() {
        val scan = ClasspathAssetCatalogScanner(
            listOf(AssetIndexFixtures.jarRoot("{ this is not json", "broken.jar")),
        ).scan()

        val problem = assertIs<AssetCatalogProblem.Malformed>(scan.problems.single())
        assertTrue(problem.reason.isNotBlank())
        assertEquals("broken.jar", problem.origin)
    }

    @Test
    fun `a classpath entry that is not an archive is a problem, not a crash`() {
        val notAJar = File(AssetIndexFixtures.emptyRoot(), "notes.txt").apply { writeText("hello") }

        val scan = ClasspathAssetCatalogScanner(listOf(notAJar)).scan()

        assertIs<AssetCatalogProblem.Malformed>(scan.problems.single())
    }

    /**
     * Issue #40's "at most once per compilation", with a counting fake rather than a comment.
     *
     * The count is the whole assertion: without the cache the scanner runs once per
     * `reference("...")` in the module, which on the MOBA is hundreds of classpath walks.
     */
    @Test
    fun `the classpath is scanned at most once, however many times the result is asked for`() {
        val scans = AtomicInteger()
        val source = AssetCatalogSource {
            scans.incrementAndGet()
            AssetCatalogScan.EMPTY
        }

        assertEquals(0, scans.get(), "constructing the source must not scan anything")
        repeat(50) { source.scan() }

        assertEquals(1, scans.get())
    }

    @Test
    fun `the cached result is the same instance every time`() {
        val source = AssetCatalogSource {
            AssetCatalogScan(AssetIndexFixtures.exampleCatalog(), emptyList())
        }

        assertSame(source.scan(), source.scan())
    }
}
