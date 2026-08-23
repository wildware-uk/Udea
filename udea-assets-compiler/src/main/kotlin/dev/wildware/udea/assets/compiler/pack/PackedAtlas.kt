package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.pack.AtlasRegion

/**
 * The result of packing: page pixels, where every frame landed, and which pages the eager set
 * needs.
 *
 * [regions] is sorted by name and [sheetRanges] indexes into it, which is what lets the runtime
 * answer "the frames of `character/orc_idle`" with a range rather than a scan.
 */
public class PackedAtlas(
    /** PNG bytes of each page, in page order. */
    public val pages: List<ByteArray>,
    /** `(width, height)` of each page, in page order. */
    public val pageSizes: List<Pair<Int, Int>>,
    /** Every frame, sorted by [AtlasRegion.name]. */
    public val regions: List<AtlasRegion>,
    /** Sheet id to its contiguous slice of [regions]. */
    public val sheetRanges: Map<String, IntRange>,
    /** Pages the eager set draws from; the rest stream. */
    public val eagerPages: Set<Int> = emptySet(),
) {
    init {
        require(pages.size == pageSizes.size) {
            "${pages.size} page image(s) but ${pageSizes.size} page size(s)"
        }
        require(eagerPages.all { it in pages.indices }) {
            "eagerPages $eagerPages names a page outside 0..${pages.size - 1}"
        }
    }

    public val frameCount: Int get() = regions.size

    override fun toString(): String = "PackedAtlas(${pages.size} pages, ${regions.size} regions)"

    public companion object {
        /** A pack with no sprites: a headless server's bundle, or a game that has none yet. */
        public val EMPTY: PackedAtlas = PackedAtlas(emptyList(), emptyList(), emptyList(), emptyMap())
    }
}
