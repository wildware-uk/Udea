package dev.wildware.udea.assets.pack

import dev.wildware.udea.assets.AssetId

/** One sub-rectangle of an atlas page, in pixels, top-left origin. */
public data class AtlasRegion(
    /** `sheetId#frame`, with the frame zero-padded so region order is name order. */
    public val name: String,
    public val page: Int,
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
) {
    init {
        require(page >= 0) { "region '$name' is on page $page" }
        require(x >= 0 && y >= 0) { "region '$name' is at ($x, $y)" }
        require(width > 0 && height > 0) { "region '$name' is ${width}x$height" }
    }
}

/** The dimensions of one packed page. Its pixels live in a separate section. */
public data class AtlasPageInfo(public val width: Int, public val height: Int) {
    init {
        require(width > 0 && height > 0) { "an atlas page is ${width}x$height" }
    }
}

/**
 * Where every sprite frame ended up.
 *
 * This is what replaces runtime `TextureRegion.split` (`common/.../animationSets.kt:25-46`).
 * The old code split a sheet into frames *on the GPU-side texture it had just uploaded*, which
 * meant one texture per sheet and therefore one bind per unit type per draw. Here the split
 * happened at pack time and the frames of forty characters share a handful of pages, so the
 * bind count is bounded by the page count rather than by the roster.
 */
public class AtlasIndex internal constructor(
    /** Pages in index order; `pages[i]` is the page named `BundleFormat.atlasPageSection(i)`. */
    public val pages: List<AtlasPageInfo>,
    /** Every region, sorted by [AtlasRegion.name]. */
    public val regions: List<AtlasRegion>,
    private val sheetRanges: Map<String, IntRange>,
) {
    private val byName: Map<String, AtlasRegion> = regions.associateBy { it.name }

    public val size: Int get() = regions.size

    /** Sheet ids this atlas holds frames for, sorted. */
    public val sheets: List<String> get() = sheetRanges.keys.sorted()

    public operator fun get(name: String): AtlasRegion? = byName[name]

    /**
     * The frames of [sheet], in frame order.
     *
     * Empty for a sheet the atlas does not hold - a bundle packed for a headless server has no
     * pages at all, and that is not an error there.
     */
    public fun framesOf(sheet: AssetId): List<AtlasRegion> =
        sheetRanges[sheet.value]?.map { regions[it] } ?: emptyList()

    /** `sheetId#0007`. The frame number is padded so the region table sorts into frame order. */
    public fun frame(sheet: AssetId, frame: Int): AtlasRegion? = byName[regionName(sheet.value, frame)]

    override fun toString(): String = "AtlasIndex(${pages.size} pages, ${regions.size} regions)"

    public companion object {

        /** Nothing packed. A server bundle, or a game with no sprites yet. */
        public val EMPTY: AtlasIndex = AtlasIndex(emptyList(), emptyList(), emptyMap())

        /**
         * An index built in memory rather than read from a bundle.
         *
         * The packer needs it to check its own output against what a runtime would see, which
         * is the one comparison that catches a writer and a reader agreeing on bytes while
         * disagreeing about what they mean.
         */
        public fun of(
            pages: List<AtlasPageInfo>,
            regions: List<AtlasRegion>,
            sheetRanges: Map<String, IntRange>,
        ): AtlasIndex {
            require(regions.map { it.name } == regions.map { it.name }.sorted()) {
                "regions must be in sorted name order; sheetRanges index into them"
            }
            sheetRanges.forEach { (sheet, range) ->
                require(range.first >= 0 && range.last < regions.size) {
                    "'$sheet' claims regions $range of ${regions.size}"
                }
            }
            return AtlasIndex(pages, regions, sheetRanges)
        }

        /** Digits the frame number is padded to. Four supports a 9999-frame sheet. */
        public const val FRAME_DIGITS: Int = 4

        /** The single spelling of a region name, shared by the packer and the reader. */
        public fun regionName(sheet: String, frame: Int): String {
            require(frame >= 0) { "frame $frame of '$sheet'" }
            return sheet + FRAME_SEPARATOR + frame.toString().padStart(FRAME_DIGITS, '0')
        }

        /**
         * `#`, which [AssetId] forbids in an id, so a region name can never collide with one.
         */
        public const val FRAME_SEPARATOR: Char = '#'

        internal fun decode(bytes: ByteArray): AtlasIndex {
            val cursor = ByteCursor(bytes, BundleFormat.ATLAS_SECTION)
            val strings = Array(cursor.count("string table", bytesEach = Int.SIZE_BYTES)) { cursor.utf8(cursor.count("string")) }
            fun stringAt(what: String): String {
                val at = cursor.i32()
                return strings.getOrNull(at) ?: cursor.corrupt("$what names string $at of ${strings.size}")
            }

            val pages = List(cursor.count("page", bytesEach = 8)) { AtlasPageInfo(cursor.i32(), cursor.i32()) }
            val regions = List(cursor.count("region", bytesEach = 14)) {
                AtlasRegion(
                    name = stringAt("a region"),
                    page = cursor.u16(),
                    x = cursor.u16(),
                    y = cursor.u16(),
                    width = cursor.u16(),
                    height = cursor.u16(),
                )
            }
            val ranges = buildMap<String, IntRange> {
                repeat(cursor.count("sheet", bytesEach = 12)) {
                    val sheet = stringAt("a sheet")
                    val first = cursor.count("first region of '$sheet'")
                    val frames = cursor.count("frame count of '$sheet'")
                    if (first + frames > regions.size) {
                        cursor.corrupt(
                            "sheet '$sheet' claims frames $first..${first + frames - 1} of " +
                                "${regions.size} regions",
                        )
                    }
                    put(sheet, first until first + frames)
                }
            }
            if (cursor.remaining != 0) {
                cursor.corrupt("${cursor.remaining} trailing byte(s) after the sheet table")
            }
            return AtlasIndex(pages, regions, ranges)
        }
    }
}
