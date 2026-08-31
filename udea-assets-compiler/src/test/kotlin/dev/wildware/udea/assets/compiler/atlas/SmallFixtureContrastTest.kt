package dev.wildware.udea.assets.compiler.atlas

import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The control for [AtlasPackerContract]: the fixture a determinism test would *naturally* have
 * used, and what it fails to notice.
 *
 * ## Why a control is needed at all
 *
 * `reversing the input order changes nothing` is only worth its seconds if it can go red, and
 * whether it can go red is a property of the **corpus**, not of the assertion. Deleting
 * `.thenBy { it.name }` from [AtlasPacker]'s comparator - which is precisely "revert the
 * tie-break to arrival order", since the remaining sort is stable - turns that test red against
 * [SyntheticArt] and leaves this one green. That pair is the evidence that the substitute corpus
 * issue #168 introduced replaced something rather than merely existing.
 *
 * ## What this test shows, and what it does not
 *
 * It shows that three sheets whose frames are **three different sizes** are totally ordered by
 * `(height desc, width desc)` before the id tie-break is ever consulted, so the layout is
 * order-independent whether or not the tie-break is there. That is the fixture somebody writes
 * when they need "a couple of sheets to pack", and it is blind to the defect.
 *
 * It does *not* claim that every small fixture is blind. Three sheets of 100x100 frames would
 * share the corpus's tie structure and would catch this same mutation; what they would not reach
 * is the page rollover, the shelf reset and the 2269-way tie that issue #89 chose the real corpus
 * for. The rule the two tests together enforce is the one in [CorpusShape]: the substitute keeps
 * the shape, not merely the idea.
 */
internal class SmallFixtureContrastTest {

    private val packer = AtlasPacker()

    /** Three sheets, three frame sizes, so no two frames anywhere tie on dimensions. */
    private fun variedSizeFixture(): List<ScannedSheet> {
        val root = TestPaths.scratch("varied-size-fixture")
        val sheets = listOf(
            write(root, "big", frames = 2, frameSize = 40),
            write(root, "medium", frames = 3, frameSize = 30),
            write(root, "small", frames = 4, frameSize = 20),
        )
        return sheets.also {
            assertEquals(
                3,
                it.map { sheet -> sheet.frameHeight }.distinct().size,
                "the fixture must have three distinct frame sizes or it is not the control",
            )
        }
    }

    private fun write(root: Path, name: String, frames: Int, frameSize: Int): ScannedSheet {
        val image = RgbaImage.blank(frames * frameSize, frameSize)
        for (at in image.argb.indices) image.argb[at] = OPAQUE or (at * STRIDE)
        val file = root.resolve("$name.png")
        Files.write(file, Png.encode(image))
        return ScannedSheet(
            input = SheetInput(id = "sprites/$name", file = file, columns = frames, rows = 1),
            frameWidth = frameSize,
            frameHeight = frameSize,
        )
    }

    /**
     * Named for what it runs, not for the counterfactual it illustrates.
     *
     * With the tie-break in place - which is the only way this suite ever runs - the layout is
     * order-independent, and so it is for the corpus. The interesting half is that this one stays
     * green when the tie-break is deleted and the corpus tests do not, and that is established by
     * an executed mutation transcript in `BRIEF-168.md`, not by anything here.
     */
    @Test
    fun `three sheets of three frame sizes lay out the same in either order`() {
        val fixture = variedSizeFixture()
        val sizes = fixture.associate { it.input.id to (it.frameWidth to it.frameHeight) }
        val sheets = fixture.map { it.input }

        val forward = packer.layout(sheets) { sizes.getValue(it.id) }
        val backward = packer.layout(sheets.reversed()) { sizes.getValue(it.id) }

        assertEquals(forward.regions, backward.regions)
        assertEquals(forward.pageSizes, backward.pageSizes)
    }

    /**
     * The two fixtures differ in the one way that decides whether the tie-break is exercised.
     *
     * Spelled out as an assertion rather than left in prose because it is the reason the corpus
     * has to stay full size, and a sentence in a KDoc does not fail when somebody shrinks it.
     */
    @Test
    fun `the corpus ties on dimensions everywhere and the small fixture nowhere`() {
        val fixture = variedSizeFixture()
        assertEquals(
            fixture.size,
            fixture.map { it.frameWidth to it.frameHeight }.distinct().size,
            "no two frames in the control may share a size",
        )

        val corpus = SyntheticArt.scan()
        assertEquals(
            1,
            corpus.map { it.frameWidth to it.frameHeight }.distinct().size,
            "every frame in the corpus must share one size",
        )
        assertEquals(
            CorpusShape.FRAMES,
            corpus.sumOf { it.input.frameCount },
            "and the tie has to be that many frames wide, which is what a small fixture cannot be",
        )
    }

    private companion object {
        const val OPAQUE = 0xFF shl 24

        /** Any non-zero step: the pixels only have to be readable, not meaningful. */
        const val STRIDE = 7
    }
}
