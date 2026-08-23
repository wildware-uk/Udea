package dev.wildware.udea.assets.compiler.atlas

import dev.wildware.udea.assets.pack.AtlasIndex
import dev.wildware.udea.assets.pack.AtlasRegion
import dev.wildware.udea.assets.compiler.pack.PackedAtlas
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * One sprite sheet on its way into the atlas: an id, a file, and how it splits into frames.
 *
 * The split happens here, at pack time. That is the point: `TextureRegion.split` did it at
 * runtime against an already-uploaded texture, which forced one texture per sheet and therefore
 * one bind per unit type per draw (`common/.../animationSets.kt:25-46`).
 */
public data class SheetInput(
    public val id: String,
    public val file: Path,
    public val columns: Int,
    public val rows: Int,
) {
    init {
        require(columns > 0 && rows > 0) { "sheet '$id' is ${columns}x$rows frames" }
    }

    public val frameCount: Int get() = columns * rows
}

/** What a packing run produced, before the pages are encoded. */
public data class AtlasLayout(
    public val pageSizes: List<Pair<Int, Int>>,
    public val regions: List<AtlasRegion>,
    public val sheetRanges: Map<String, IntRange>,
)

/**
 * A shelf packer, pinned in every dimension a packer normally chooses for itself.
 *
 * Issue #89 leaves the choice open - *"if libGDX `TexturePacker` cannot be made deterministic,
 * replace it with a simple shelf packer"*. It cannot, and this is the replacement.
 * `TexturePacker` picks its page size by growing until things fit, sorts its inputs by a
 * comparator that falls back on `File.getName()` after area, and reads the input directory with
 * `File.listFiles()`, whose order is the filesystem's. Each of those is a reproducibility bug
 * and none of them is reachable through its public API.
 *
 * What is pinned here:
 *
 * - **page size and padding** are parameters with fixed defaults, never derived from the input;
 * - **input order** is `(height desc, width desc, id asc)` - the id is the tie-break, and since
 *   ids are unique it is a total order, so no two runs can disagree even when every frame is
 *   the same size (which, for this art, they are: 327 sheets of 100x100 strips);
 * - **nothing is read from the filesystem's enumeration.** The caller passes a list; the packer
 *   sorts it.
 *
 * The packing itself is deliberately unclever. A MOBA's sheet set is 2269 frames of one size;
 * a MaxRects implementation would fit them into the same number of pages and give the
 * determinism argument three more branches to be wrong in.
 */
public class AtlasPacker(
    public val pageWidth: Int = DEFAULT_PAGE_SIZE,
    public val pageHeight: Int = DEFAULT_PAGE_SIZE,
    /**
     * Transparent pixels between frames.
     *
     * Two, not one: a bilinear sample at the edge of a frame reaches half a texel into its
     * neighbour, and one pixel of gap is exactly enough for that to still land in the gap only
     * at integer scale. The old renderer got away with one texture per sheet, so it had no
     * neighbours to bleed from; an atlas does.
     */
    public val padding: Int = DEFAULT_PADDING,
) {
    init {
        require(pageWidth > 0 && pageHeight > 0) { "a page is ${pageWidth}x$pageHeight" }
        require(padding >= 0) { "padding $padding" }
    }

    /** Where every frame of every sheet goes. Pure: it reads no files. */
    public fun layout(sheets: List<SheetInput>, frameSize: (SheetInput) -> Pair<Int, Int>): AtlasLayout {
        val duplicates = sheets.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "these sheet ids are packed twice: ${duplicates.sorted()}" }

        val frames = sheets.flatMap { sheet ->
            val (frameWidth, frameHeight) = frameSize(sheet)
            (0 until sheet.frameCount).map { frame ->
                Frame(AtlasIndex.regionName(sheet.id, frame), frameWidth, frameHeight)
            }
        }
        val ordered = frames.sortedWith(
            compareByDescending<Frame> { it.height }
                .thenByDescending { it.width }
                .thenBy { it.name },
        )

        val placed = ArrayList<AtlasRegion>(ordered.size)
        val pageSizes = mutableListOf<Pair<Int, Int>>()
        var page = 0
        var shelfY = 0
        var shelfHeight = 0
        var cursorX = 0

        for (frame in ordered) {
            val boxWidth = frame.width + padding
            val boxHeight = frame.height + padding
            require(boxWidth <= pageWidth && boxHeight <= pageHeight) {
                "frame '${frame.name}' is ${frame.width}x${frame.height} and does not fit a " +
                    "${pageWidth}x$pageHeight page with $padding padding"
            }
            if (cursorX + boxWidth > pageWidth) {
                cursorX = 0
                shelfY += shelfHeight
                shelfHeight = 0
            }
            if (shelfY + boxHeight > pageHeight) {
                pageSizes += pageWidth to pageHeight
                page++
                shelfY = 0
                shelfHeight = 0
                cursorX = 0
            }
            placed += AtlasRegion(frame.name, page, cursorX, shelfY, frame.width, frame.height)
            cursorX += boxWidth
            if (boxHeight > shelfHeight) shelfHeight = boxHeight
        }
        if (placed.isNotEmpty()) pageSizes += pageWidth to pageHeight

        val sorted = placed.sortedBy { it.name }
        return AtlasLayout(pageSizes, sorted, rangesOf(sorted))
    }

    /**
     * Lays the sheets out, reads their pixels, and encodes the pages.
     *
     * Reading happens after layout on purpose: a layout is a pure function of the sheet list and
     * the frame size, so `AtlasDeterminismTest` can assert two layouts are equal without any
     * image decoding at all, and a determinism failure in the *layout* is then distinguishable
     * from one in the *encoder*.
     */
    public fun pack(sheets: List<SheetInput>, eagerSheets: Set<String> = emptySet()): PackedAtlas {
        if (sheets.isEmpty()) return PackedAtlas.EMPTY
        val sources = sheets.sortedBy { it.id }.associate { it.id to read(it.file, it.id) }
        val layout = layout(sheets) { sheet ->
            val image = sources.getValue(sheet.id)
            require(image.width % sheet.columns == 0 && image.height % sheet.rows == 0) {
                "sheet '${sheet.id}' is ${image.width}x${image.height}, which does not divide " +
                    "into ${sheet.columns}x${sheet.rows} frames"
            }
            (image.width / sheet.columns) to (image.height / sheet.rows)
        }
        val bySheet = sheets.associateBy { it.id }

        val pages = layout.pageSizes.map { (w, h) -> RgbaImage.blank(w, h) }
        for (region in layout.regions) {
            val sheetId = region.name.substringBeforeLast(AtlasIndex.FRAME_SEPARATOR)
            val sheet = bySheet.getValue(sheetId)
            val source = sources.getValue(sheetId)
            val frame = region.name.substringAfterLast(AtlasIndex.FRAME_SEPARATOR).toInt()
            val column = frame % sheet.columns
            val row = frame / sheet.columns
            pages[region.page].blit(
                source = source,
                x = region.x,
                y = region.y,
                sx = column * region.width,
                sy = row * region.height,
                w = region.width,
                h = region.height,
            )
        }
        val eagerPages = layout.regions
            .filter { it.name.substringBeforeLast(AtlasIndex.FRAME_SEPARATOR) in eagerSheets }
            .map { it.page }
            .toSet()
        return PackedAtlas(
            pages = pages.map(Png::encode),
            pageSizes = layout.pageSizes,
            regions = layout.regions,
            sheetRanges = layout.sheetRanges,
            eagerPages = eagerPages,
        )
    }

    /**
     * Frames of one sheet are contiguous in the sorted region list because the frame number is
     * zero-padded, so `orc_idle#0002` sorts between `#0001` and `#0003` rather than after
     * `#0010`. That is what `AtlasIndex.FRAME_DIGITS` is for, and this check is what proves it
     * held rather than assuming it.
     */
    private fun rangesOf(regions: List<AtlasRegion>): Map<String, IntRange> {
        val ranges = LinkedHashMap<String, IntRange>()
        regions.forEachIndexed { at, region ->
            val sheet = region.name.substringBeforeLast(AtlasIndex.FRAME_SEPARATOR)
            val existing = ranges[sheet]
            ranges[sheet] = if (existing == null) at..at else existing.first..at
        }
        ranges.forEach { (sheet, range) ->
            val names = range.map { regions[it].name.substringBeforeLast(AtlasIndex.FRAME_SEPARATOR) }
            check(names.all { it == sheet }) {
                "the frames of '$sheet' are not contiguous in name order, so a range cannot " +
                    "address them; AtlasIndex.FRAME_DIGITS is too small for a sheet this long"
            }
        }
        return ranges
    }

    private fun read(file: Path, id: String): RgbaImage {
        val image = ImageIO.read(file.toFile())
            ?: error("'$id' names $file, which ImageIO could not read as an image")
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        return RgbaImage(image.width, image.height, argb)
    }

    private class Frame(val name: String, val width: Int, val height: Int)

    public companion object {
        /**
         * 2048, the largest page every GL2/GLES2 device is required to support.
         *
         * A larger page would fit this art in fewer binds on a desktop and fail to upload at all
         * on a phone. A packer that chose the size by growing until it fit would produce a
         * different size the day someone adds a character, which is the reproducibility bug.
         */
        public const val DEFAULT_PAGE_SIZE: Int = 2048

        public const val DEFAULT_PADDING: Int = 2
    }
}
