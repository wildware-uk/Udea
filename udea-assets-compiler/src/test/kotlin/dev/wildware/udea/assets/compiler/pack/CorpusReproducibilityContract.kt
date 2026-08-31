package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.atlas.AtlasPacker
import dev.wildware.udea.assets.compiler.atlas.SheetInput
import dev.wildware.udea.assets.compiler.atlas.SpriteCorpus
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two packs of a full-size corpus agree byte for byte, whichever corpus that is.
 *
 * One body, two runs, for [dev.wildware.udea.assets.compiler.atlas.AtlasPackerContract]'s reason:
 * [ReproducibilityTest] runs it against the synthetic corpus everywhere, and
 * [RealArtReproducibilityTest] against the paid art where that exists.
 *
 * It also carries the two-checkout machinery, because [ReproducibilityTest]'s own bundle tests
 * need it and there is no sense in two copies of a helper whose whole point is that the two roots
 * differ in a controlled way.
 */
internal abstract class CorpusReproducibilityContract {

    protected abstract val corpus: SpriteCorpus

    /**
     * Distinguishes this run's scratch directories from the other subclass's, and is the string
     * `no absolute path appears anywhere in the bundle` searches the artifact for.
     */
    protected open val checkoutPrefix: String = "repro"

    /** Does nothing by default, so the synthetic run cannot skip. */
    protected open fun requireCorpus(): Unit = Unit

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
    protected fun twoCheckouts(): Pair<Path, Path> =
        checkoutAt("$checkoutPrefix-a") to checkoutAt("$checkoutPrefix-b-with-a-much-longer-name")

    /**
     * The full corpus is byte-identical across two packs, pages included.
     *
     * The graph half in [ReproducibilityTest] is cheap; this is the expensive half and the one
     * issue #89 flags as "the fiddliest part". The `SheetInput` lists are built from two
     * *separate* directory walks, so a dependence on `Files.walk` order would show up here.
     *
     * Both packs are written out under `build/reports/udea/atlas/` on the way past, as
     * `<corpus>-forward-page-NN.png` and `<corpus>-reversed-page-NN.png`. The assertion below
     * says those two sets are the same bytes, so a person can check the gate's claim by looking
     * at two pictures - and when a tie-break breaks, the difference is a wall of colour in a
     * different order rather than two hashes that disagree.
     */
    @Test
    fun `two packs of the whole art corpus produce identical atlas pages`() {
        requireCorpus()
        val packer = AtlasPacker()

        val first = packer.pack(corpus.sheets())
        val second = packer.pack(corpus.sheets().reversed())
        publish("forward", first)
        publish("reversed", second)

        assertEquals(first.pages.size, second.pages.size, "different page counts")
        assertTrue(first.pages.isNotEmpty(), "the corpus packed into no pages at all")
        first.pages.forEachIndexed { page, bytes ->
            assertEquals(sha256(bytes), sha256(second.pages[page]), "atlas page $page differs")
        }
        assertEquals(first.regions, second.regions)
        assertEquals(first.sheetRanges, second.sheetRanges)
    }

    /** A bundle carrying real atlas pages is byte-identical across two packs. */
    @Test
    fun `a bundle carrying atlas pages is byte-identical across two packs`() {
        requireCorpus()
        val (first, second) = twoCheckouts()
        // One character's sheets: the whole corpus is exercised by the test above, and a
        // six-page atlas in a bundle comparison would make this test a minute long for no
        // additional coverage of the *writer*.
        val sheets: List<SheetInput> = corpus.sampleCharacterSheets()
        assertTrue(sheets.isNotEmpty(), "${corpus.name} has no sheets under its sample character")
        val packer = AtlasPacker()

        val a = PackFixture.bundle(
            first,
            first.resolve(ASSETS),
            "$checkoutPrefix-atlas-a",
            packer.pack(sheets),
        )
        val b = PackFixture.bundle(
            second,
            second.resolve(ASSETS),
            "$checkoutPrefix-atlas-b",
            packer.pack(sheets.reversed()),
        )

        assertContentEquals(a, b)
    }

    /** Writes the packed pages where a person, and the CI artifact upload, can look at them. */
    private fun publish(label: String, atlas: PackedAtlas) {
        val into = TestPaths.repoRoot.resolve("udea-assets-compiler/build/reports/udea/atlas")
        into.createDirectories()
        atlas.pages.forEachIndexed { page, bytes ->
            val name = "${corpus.name}-$label-page-${page.toString().padStart(2, '0')}.png"
            into.resolve(name).writeBytes(bytes)
        }
    }

    protected fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    protected companion object {
        const val ASSETS = PackFixture.ASSETS
    }
}
