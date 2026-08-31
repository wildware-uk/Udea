package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.atlas.MobaArt
import dev.wildware.udea.assets.compiler.atlas.SpriteCorpus
import dev.wildware.udea.assets.compiler.atlas.SyntheticArt
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
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
 *
 * ## The two atlas tests it inherits
 *
 * They come from [CorpusReproducibilityContract] and run against [SyntheticArt], which is why
 * this class no longer skips on a checkout with no paid art (issue #168).
 * [RealArtReproducibilityTest] is the same pair pointed at the real corpus.
 */
internal class ReproducibilityTest : CorpusReproducibilityContract() {

    override val corpus: SpriteCorpus = SyntheticArt

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

        for (fragment in listOf(first.toString(), first.toString().replace('\\', '/'), "$checkoutPrefix-a")) {
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
}

/**
 * The two corpus packs against the real art, which only a machine holding the paid archives has.
 *
 * Kept as the additional run rather than deleted: the synthetic corpus is drawn by this
 * repository's own PNG encoder, so it cannot stand in for decoding somebody else's PNGs and
 * blitting them. It skips when the art is absent; [ReproducibilityTest] has already proved the
 * property in the same task by then.
 */
internal class RealArtReproducibilityTest : CorpusReproducibilityContract() {

    override val corpus: SpriteCorpus = MobaArt

    override val checkoutPrefix: String = "repro-real-art"

    override fun requireCorpus(): Unit = assumeTrue(corpus.available, corpus.unavailable)
}
