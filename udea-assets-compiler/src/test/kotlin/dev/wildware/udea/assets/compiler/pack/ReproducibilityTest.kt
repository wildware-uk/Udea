package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.atlas.AtlasPacker
import dev.wildware.udea.assets.compiler.atlas.MobaArt
import dev.wildware.udea.assets.compiler.atlas.SheetInput
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2's exit criterion: two clean builds produce a byte-identical `.udeapak`.
 *
 * ## Two directories, not two runs
 *
 * Issue #89 says "including when run from two different checkout directories", and that phrase
 * is the whole test. Packing twice from one directory would prove almost nothing: the failure
 * this criterion exists to catch is an **absolute path leaking into the artifact**, and a path
 * that leaks is the same path both times when the directory is the same. So the fixture tree is
 * copied to two scratch directories with deliberately different names and lengths, and both are
 * packed with *their own* directory as the repo root.
 *
 * The scan's spans - which are repo-relative and therefore differ if relativisation is broken -
 * are not in the bundle at all, so a leak would have to come through a `ResPath` or through the
 * `AssetCompiler`'s own cache key. [`no absolute path appears anywhere in the bundle`] checks
 * the artifact directly for the directory names, which catches a leak this comparison would
 * miss if both packs leaked the *same* wrong thing.
 */
class ReproducibilityTest {

    @OptIn(ExperimentalPathApi::class)
    private fun checkoutAt(name: String): Path {
        val root = TestPaths.scratch(name)
        val assets = root.resolve(ASSETS)
        assets.parent.createDirectories()
        PackFixture.assetRoot.copyToRecursively(assets, followLinks = false, overwrite = true)
        return root
    }

    /**
     * The two roots have different names *and* different lengths.
     *
     * Equal-length names would hide a leak that wrote a fixed-width path, and a leak of a
     * fixed-width path is exactly what a naive `String.format` of a directory produces.
     */
    private fun twoCheckouts(): Pair<Path, Path> =
        checkoutAt("repro-a") to checkoutAt("repro-b-with-a-much-longer-name")

    @Test
    fun `two packs from two different checkout directories are byte-identical`() {
        val (first, second) = twoCheckouts()

        val a = PackFixture.bundle(first, first.resolve(ASSETS), "repro-cache-a")
        val b = PackFixture.bundle(second, second.resolve(ASSETS), "repro-cache-b")

        assertEquals(sha256(a), sha256(b), "the two bundles differ; first difference at ${diffAt(a, b)}")
        assertContentEquals(a, b)
    }

    /**
     * No directory name from either checkout survives into the bytes.
     *
     * Searched as raw bytes rather than by decoding, because a leak could be in any of the
     * string table, a section name or a `ResPath`, and a decoder that skipped the leaking field
     * would report clean.
     */
    @Test
    fun `no absolute path appears anywhere in the bundle`() {
        val (first, _) = twoCheckouts()
        val bytes = PackFixture.bundle(first, first.resolve(ASSETS), "repro-cache-paths")

        for (fragment in listOf(first.toString(), first.toString().replace('\\', '/'), "repro-a")) {
            assertTrue(
                indexOf(bytes, fragment.toByteArray(Charsets.UTF_8)) < 0,
                "'$fragment' leaked into the bundle at byte ${indexOf(bytes, fragment.toByteArray())}",
            )
        }
        // The repo root itself, which is where a `SourceSpan` would have come from.
        assertTrue(
            indexOf(bytes, TestPaths.repoRoot.toString().toByteArray(Charsets.UTF_8)) < 0,
            "the repository root leaked into the bundle",
        )
    }

    /**
     * The full 327-sheet atlas is byte-identical across two packs, pages included.
     *
     * The graph half above is cheap; this is the expensive half and the one issue #89 flags as
     * "the fiddliest part". The `SheetInput` lists are built from two *separate* directory
     * walks, so a dependence on `Files.walk` order would show up here.
     */
    @Test
    fun `two packs of the whole art corpus produce identical atlas pages`() {
        assumeTrue(MobaArt.available, "moba sprite art is absent; run python scripts/extract-art.py")
        val packer = AtlasPacker()

        val first = packer.pack(MobaArt.sheets())
        val second = packer.pack(MobaArt.sheets().reversed())

        assertEquals(first.pages.size, second.pages.size, "different page counts")
        assertTrue(first.pages.isNotEmpty(), "the corpus packed into no pages at all")
        first.pages.forEachIndexed { page, bytes ->
            assertEquals(sha256(bytes), sha256(second.pages[page]), "atlas page $page differs")
        }
        assertEquals(first.regions, second.regions)
        assertEquals(first.sheetRanges, second.sheetRanges)
    }

    /** A bundle carrying the real atlas is byte-identical across two packs. */
    @Test
    fun `a bundle carrying real atlas pages is byte-identical across two packs`() {
        assumeTrue(MobaArt.available, "moba sprite art is absent; run python scripts/extract-art.py")
        val (first, second) = twoCheckouts()
        // One character's sheets: the whole corpus is exercised by the test above, and a
        // six-page atlas in a bundle comparison would make this test a minute long for no
        // additional coverage of the *writer*.
        val sheets: List<SheetInput> =
            MobaArt.sheets().filter { it.id.startsWith("sprites/champions/archer/") }
        val packer = AtlasPacker()

        val a = PackFixture.bundle(first, first.resolve(ASSETS), "repro-atlas-a", packer.pack(sheets))
        val b = PackFixture.bundle(second, second.resolve(ASSETS), "repro-atlas-b", packer.pack(sheets.reversed()))

        assertContentEquals(a, b)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun diffAt(a: ByteArray, b: ByteArray): String {
        val at = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: return "the tail"
        return "byte $at (of ${a.size} and ${b.size})"
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (at in needle.indices) if (haystack[start + at] != needle[at]) continue@outer
            return start
        }
        return -1
    }

    private companion object {
        const val ASSETS = PackFixture.ASSETS
    }
}
