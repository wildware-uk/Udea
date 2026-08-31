package dev.wildware.udea.assets.compiler.atlas

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.pack.AtlasIndex
import dev.wildware.udea.assets.pack.AtlasPageInfo
import dev.wildware.udea.assets.pack.AtlasRegion
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the packer must do against a full-size corpus, whichever corpus that is.
 *
 * One body, two runs: [AtlasPackerTest] runs it against [SyntheticArt] on every machine, and
 * [RealArtAtlasPackerTest] runs it against [MobaArt] on a machine holding the paid archives.
 * Written as a base class rather than copied so the two cannot drift, which matters more here
 * than usual - the second run exists precisely to confirm the first one is checking the same
 * thing.
 */
internal abstract class AtlasPackerContract {

    protected abstract val corpus: SpriteCorpus

    private val packer = AtlasPacker()

    /**
     * Called before any corpus is touched. The default does nothing, which is what makes the
     * synthetic run unable to skip; [RealArtAtlasPackerTest] overrides it with the assumption.
     */
    protected open fun requireCorpus(): Unit = Unit

    private fun scan(): List<ScannedSheet> {
        requireCorpus()
        return corpus.scan()
    }

    private fun sheetsOf(scanned: List<ScannedSheet>): List<SheetInput> = scanned.map { it.input }

    /**
     * Frame size taken from the images rather than from a constant.
     *
     * The old constant said 100 whatever the file held, so a corpus with an odd sheet in it would
     * have been laid out against a size it does not have and the overlap check would have passed
     * on a lie.
     */
    private fun frameSizes(scanned: List<ScannedSheet>): (SheetInput) -> Pair<Int, Int> {
        val bySheet = scanned.associate { it.input.id to (it.frameWidth to it.frameHeight) }
        return { sheet -> bySheet.getValue(sheet.id) }
    }

    /**
     * The corpus is the shape issue #89 chose, and every frame in it is the same size.
     *
     * The last of those is the load-bearing one and it is why this test is worth its seconds:
     * [AtlasPacker] orders by `(height desc, width desc, id asc)`, so a corpus of one frame size
     * is a corpus in which the first two keys separate *nothing* and the id tie-break decides all
     * 2269 placements alone. Shrink the corpus, or let two frame sizes into it, and
     * `reversing the input order changes nothing` stops being able to fail - which is exactly the
     * defect issue #168 was filed to close, arriving by the back door.
     */
    @Test
    fun `the corpus is the shape issue 89 chose`() {
        val scanned = scan()
        val sheets = sheetsOf(scanned)

        assertEquals(CorpusShape.SHEETS, sheets.size, "${corpus.name}: sheets")
        assertEquals(CorpusShape.FRAMES, sheets.sumOf { it.frameCount }, "${corpus.name}: frames")
        assertEquals(
            CorpusShape.CHARACTERS,
            sheets.filter { it.id.startsWith(CorpusShape.CHAMPIONS) }
                .map { it.id.removePrefix(CorpusShape.CHAMPIONS).substringBefore('/') }
                .distinct().size,
            "${corpus.name}: characters",
        )
        assertTrue(sheets.all { it.rows == 1 }, "every sheet is a one-row strip")
        assertEquals(
            setOf(CorpusShape.FRAME_SIZE to CorpusShape.FRAME_SIZE),
            scanned.map { it.frameWidth to it.frameHeight }.toSet(),
            "every frame must be one size, or the id tie-break stops being what decides the " +
                "layout; the odd sheets are " +
                scanned.filter { it.frameWidth != CorpusShape.FRAME_SIZE || it.frameHeight != CorpusShape.FRAME_SIZE }
                    .map { it.input.id },
        )
    }

    /**
     * The layout does not depend on the order the sheets were handed over.
     *
     * Reversed rather than shuffled: a shuffle with an unseeded random is a test that fails one
     * run in a hundred and passes the rerun, and reversing is the permutation most likely to
     * expose an accidental dependence on arrival order.
     */
    @Test
    fun `reversing the input order changes nothing`() {
        val scanned = scan()
        val sheets = sheetsOf(scanned)
        val frameSize = frameSizes(scanned)

        val forward = packer.layout(sheets, frameSize)
        val backward = packer.layout(sheets.reversed(), frameSize)

        assertEquals(forward.regions, backward.regions)
        assertEquals(forward.pageSizes, backward.pageSizes)
        assertEquals(forward.sheetRanges, backward.sheetRanges)
    }

    @Test
    fun `every frame lands inside its page and no two overlap`() {
        val scanned = scan()
        val layout = packer.layout(sheetsOf(scanned), frameSizes(scanned))

        assertTrue(layout.regions.isNotEmpty())
        for (region in layout.regions) {
            val (width, height) = layout.pageSizes[region.page]
            assertTrue(
                region.x + region.width <= width && region.y + region.height <= height,
                "${region.name} at (${region.x}, ${region.y}) runs off page ${region.page}",
            )
        }
        layout.regions.groupBy { it.page }.forEach { (page, regions) ->
            val sorted = regions.sortedWith(compareBy({ it.y }, { it.x }))
            for (i in sorted.indices) {
                for (j in i + 1 until sorted.size) {
                    if (sorted[j].y >= sorted[i].y + sorted[i].height) break
                    assertTrue(
                        !overlaps(sorted[i], sorted[j]),
                        "on page $page, ${sorted[i].name} and ${sorted[j].name} overlap",
                    )
                }
            }
        }
    }

    private fun overlaps(a: AtlasRegion, b: AtlasRegion): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    @Test
    fun `every declared frame of every sheet is placed exactly once`() {
        val scanned = scan()
        val sheets = sheetsOf(scanned)

        val layout = packer.layout(sheets, frameSizes(scanned))

        assertEquals(sheets.sumOf { it.frameCount }, layout.regions.size)
        assertEquals(layout.regions.size, layout.regions.map { it.name }.distinct().size)
        for (sheet in sheets) {
            val range = assertNotNull(layout.sheetRanges[sheet.id], "'${sheet.id}' has no frame range")
            assertEquals(sheet.frameCount, range.last - range.first + 1, "'${sheet.id}' frame count")
            assertContentEquals(
                (0 until sheet.frameCount).map { AtlasIndex.regionName(sheet.id, it) },
                range.map { layout.regions[it].name },
                "'${sheet.id}' frames are not contiguous and in frame order",
            )
        }
    }

    /**
     * Packing - decode, blit, encode - is byte-identical run to run.
     *
     * Restricted to one character's sheets so the test is seconds rather than a minute; the
     * whole corpus goes through `ReproducibilityTest`, which is the gate.
     */
    @Test
    fun `packing the same sheets twice produces the same pages`() {
        val sheets = sampleCharacter()

        val first = packer.pack(sheets)
        val second = packer.pack(sheets.reversed())

        assertEquals(first.pages.size, second.pages.size)
        first.pages.forEachIndexed { page, bytes ->
            assertContentEquals(bytes, second.pages[page], "atlas page $page differs between runs")
        }
        assertEquals(first.regions, second.regions)
    }

    /** The blit puts the right frame at the right place, checked pixel for pixel. */
    @Test
    fun `a packed frame holds the pixels of the source frame it names`() {
        val sheets = sampleCharacter().take(3)

        val packed = packer.pack(sheets)
        val decoded = packed.pages.map { PngTestSupport.decode(it) }

        for (sheet in sheets) {
            val source = PngTestSupport.read(sheet.file)
            for (frame in 0 until sheet.frameCount) {
                val region = packed.regions.single { it.name == AtlasIndex.regionName(sheet.id, frame) }
                val page = decoded[region.page]
                val frameWidth = source.width / sheet.columns
                for (y in 0 until region.height) {
                    for (x in 0 until region.width) {
                        assertEquals(
                            source[frame * frameWidth + x, y],
                            page[region.x + x, region.y + y],
                            "${region.name} pixel ($x, $y)",
                        )
                    }
                }
            }
        }
    }

    /** The reader's frame lookup agrees with the packer's region names. */
    @Test
    fun `framesOf on the decoded index returns the packed frames`() {
        val sheets = sampleCharacter().take(2)
        val packed = packer.pack(sheets)

        val index = AtlasIndex.of(
            packed.pageSizes.map { (w, h) -> AtlasPageInfo(w, h) },
            packed.regions,
            packed.sheetRanges,
        )

        for (sheet in sheets) {
            assertEquals(sheet.frameCount, index.framesOf(AssetId(sheet.id)).size)
            assertNotNull(index.frame(AssetId(sheet.id), 0))
        }
    }

    private fun sampleCharacter(): List<SheetInput> {
        val sheets = sheetsOf(scan()).filter { it.id.startsWith(corpus.sampleCharacter) }
        assertTrue(
            sheets.isNotEmpty(),
            "${corpus.name} has no sheets under '${corpus.sampleCharacter}'",
        )
        return sheets
    }
}
