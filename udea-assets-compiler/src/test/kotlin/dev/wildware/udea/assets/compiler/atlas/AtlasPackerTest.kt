package dev.wildware.udea.assets.compiler.atlas

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.pack.AtlasIndex
import dev.wildware.udea.assets.pack.AtlasPageInfo
import dev.wildware.udea.assets.pack.AtlasRegion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The packer against the real 327-sheet corpus. */
class AtlasPackerTest {

    private val packer = AtlasPacker()

    private fun realSheets(): List<SheetInput> {
        assumeTrue(MobaArt.available, "moba sprite art is absent; run python scripts/extract-art.py")
        return MobaArt.sheets()
    }

    private fun squareFrames(sheet: SheetInput): Pair<Int, Int> = FRAME to FRAME

    @Test
    fun `the corpus is the one issue 89 describes`() {
        val sheets = realSheets()

        assertEquals(327, sheets.size, "327 sheets")
        assertEquals(
            40,
            sheets.filter { it.id.startsWith("sprites/champions/") }
                .map { it.id.removePrefix("sprites/champions/").substringBefore('/') }
                .distinct().size,
            "40 characters",
        )
        assertTrue(sheets.all { it.rows == 1 }, "every sheet is a one-row strip")
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
        val sheets = realSheets()

        val forward = packer.layout(sheets, ::squareFrames)
        val backward = packer.layout(sheets.reversed(), ::squareFrames)

        assertEquals(forward.regions, backward.regions)
        assertEquals(forward.pageSizes, backward.pageSizes)
        assertEquals(forward.sheetRanges, backward.sheetRanges)
    }

    @Test
    fun `every frame lands inside its page and no two overlap`() {
        val layout = packer.layout(realSheets(), ::squareFrames)

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
        val sheets = realSheets()

        val layout = packer.layout(sheets, ::squareFrames)

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
        val sheets = realSheets().filter { it.id.startsWith("sprites/champions/archer/") }
        assertTrue(sheets.isNotEmpty(), "the archer's sheets are missing from the corpus")

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
        val sheets = realSheets().filter { it.id.startsWith("sprites/champions/archer/") }.take(3)

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
        val sheets = realSheets().filter { it.id.startsWith("sprites/champions/archer/") }.take(2)
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

    private companion object {
        /** Every frame in this corpus is 100x100; the art is horizontal strips of squares. */
        const val FRAME = 100
    }
}
