package dev.wildware.udea.render

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import dev.wildware.udea.assets.AssetIndex

/**
 * One packed atlas entry, as pass 4 wrote it and the runtime reads it.
 *
 * ## Every field here is decided at pack time, and that is the whole point
 *
 * The path this replaces did the opposite: `SpriteSheet(spritePath, columns, rows, scale)` in
 * `common/.../assets/render.kt:19` divided a texture into a grid *at runtime*, and
 * `SpriteRenderer.loadSprite()` reached through `gameScreen.gameManager.assetManager` during DSL
 * evaluation and multiplied by a hardcoded `WORLD_SCALE = 0.1F`. Three consequences followed:
 * one texture per sheet and therefore a texture bind per unit type per draw; a world size that
 * no authored value could override; and a `TextureRegion` sitting in a component, which is a GL
 * handle in a thing that spec 3.6 says may only ever hold a pack-time-stable int.
 *
 * So [pivotX]/[pivotY] and [pixelsPerUnit] are carried rather than assumed. A region's world
 * size is `width / pixelsPerUnit`, which is an authored fact about the art; it is not a global
 * constant, and it is not the renderer's to choose.
 *
 * @param index the pack-time interned slot. The only asset identity allowed into a snapshot.
 * @param page which atlas page's texture the region is cut from.
 * @param x left edge in page pixels, y **top** edge in page pixels (LibGDX's convention).
 * @param pivotX the region-relative pivot, `0..1`, where `0.5` is the middle.
 */
public data class PackedRegion(
    public val index: AssetIndex,
    public val page: Int,
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
    public val pivotX: Float = 0.5f,
    public val pivotY: Float = 0.5f,
    public val pixelsPerUnit: Float = 100f,
) {
    init {
        require(page >= 0) { "atlas page $page is negative for index $index" }
        require(width > 0 && height > 0) { "region $index is ${width}x$height" }
        require(pixelsPerUnit > 0f && pixelsPerUnit.isFinite()) {
            "region $index has pixelsPerUnit $pixelsPerUnit; a region with no scale has no world size"
        }
    }
}

/**
 * `AssetIndex` → `TextureRegion`, as a flat array read.
 *
 * ## Why an array and not a map
 *
 * This is consulted once per drawn entity per frame, which at a few hundred entities and 60Hz is
 * tens of thousands of lookups a second. The thing it replaces was a `by lazy` hash lookup into
 * a global `object Assets` map (`AssetRefImpl.value`), so every draw paid a `hashCode`, a bucket
 * walk and a `String` comparison for a value that never changes. An [AssetIndex] is a slot, so
 * the lookup is `regions[index.value]` — one bounds check, one load, no hashing and nothing
 * allocated. `RenderAssetsTest` asserts the no-allocation half rather than asserting it here.
 *
 * ## Missing slots are absent, not empty
 *
 * A graph legitimately holds assets that are not sprites — a sound cue, a blueprint — so most
 * indices have no region. Those read back `null` from [regionOrNull] and throw from [region],
 * and the message names the index. A renderer that silently drew nothing for a mistyped
 * reference is how the old tree produced an invisible unit and no diagnostic.
 *
 * ## It owns nothing
 *
 * The page [Texture]s belong to whoever created them (in practice `RenderResources.own`, which
 * disposes with the pipeline). This holds regions that point into them and has no `dispose`, so
 * there is no arrangement in which a second owner disposes a texture the pipeline still draws.
 */
public class RenderAssets private constructor(
    private val regions: Array<TextureRegion?>,
    private val worldWidths: FloatArray,
    private val worldHeights: FloatArray,
    private val pivotXs: FloatArray,
    private val pivotYs: FloatArray,
) {

    /** One past the highest [AssetIndex] this table can answer for. */
    public val size: Int get() = regions.size

    /** The region at [index], or `null` when nothing was packed there. Never throws. */
    public fun regionOrNull(index: AssetIndex): TextureRegion? =
        if (index.value < regions.size) regions[index.value] else null

    /**
     * The region at [index].
     *
     * @throws IllegalArgumentException when nothing is packed there, naming the index. Loud,
     *   because the alternative — drawing nothing — is a bug that looks like art direction.
     */
    public fun region(index: AssetIndex): TextureRegion = requireNotNull(regionOrNull(index)) {
        "no atlas region is packed at $index; the table holds $size slots"
    }

    /** Width in world units: the packed pixel width over the region's pack-time scale. */
    public fun worldWidth(index: AssetIndex): Float = worldWidths[checked(index)]

    /** Height in world units. @see worldWidth */
    public fun worldHeight(index: AssetIndex): Float = worldHeights[checked(index)]

    /** Region-relative pivot in `0..1`, from pack-time metadata rather than a renderer default. */
    public fun pivotX(index: AssetIndex): Float = pivotXs[checked(index)]

    /** @see pivotX */
    public fun pivotY(index: AssetIndex): Float = pivotYs[checked(index)]

    /**
     * The **one** place a world-space draw rectangle is computed.
     *
     * Issue #123 asks for a single region-lookup helper so that the sprite, animation and tile
     * renderers cannot diverge on pivot handling — and they had, in the tree this replaces:
     * `SpriteBatchSystem` drew from the body origin, `DebugDrawSystem` drew from a corner, and
     * a sprite's apparent position depended on which system had drawn it. Writing the arithmetic
     * once and calling it from all three is the fix that keeps working when a fourth arrives.
     *
     * Writes into [into] rather than returning a rectangle, because this is on the per-entity
     * draw path and a returned object is an allocation per sprite per frame.
     *
     * @param into `[x, y, width, height]` in world units, left-bottom origin as `Batch.draw`
     *   wants it.
     */
    public fun worldQuad(index: AssetIndex, worldX: Float, worldY: Float, into: FloatArray) {
        require(into.size >= QUAD_SIZE) { "worldQuad writes $QUAD_SIZE floats; got ${into.size}" }
        val slot = checked(index)
        val width = worldWidths[slot]
        val height = worldHeights[slot]
        into[0] = worldX - width * pivotXs[slot]
        into[1] = worldY - height * pivotYs[slot]
        into[2] = width
        into[3] = height
    }

    private fun checked(index: AssetIndex): Int {
        require(index.value < regions.size) {
            "$index is outside this atlas table's $size slots"
        }
        return index.value
    }

    public companion object {

        /** How many floats [worldQuad] writes. */
        public const val QUAD_SIZE: Int = 4

        /** An atlas with nothing in it. What a game with no packed sprites gets. */
        public val EMPTY: RenderAssets = RenderAssets(
            emptyArray(), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
        )

        /**
         * Builds the table from the pages a `.udeapak` carried and the regions it declared.
         *
         * The array is sized to the **highest index present**, not to the number of regions: the
         * indices are pack-time slots into the whole asset graph, so a sprite at index 900 in a
         * graph of 1000 assets is normal and compacting them would break the one property an
         * `AssetIndex` exists for.
         *
         * @throws IllegalArgumentException on a duplicate index or a page a caller did not
         *   supply. Both are producer bugs, and a table built around one is a renderer that
         *   draws the wrong picture without saying so.
         */
        public fun of(pages: List<Texture>, regions: List<PackedRegion>): RenderAssets =
            build(regions) { region ->
                val page = pages.getOrNull(region.page)
                requireNotNull(page) {
                    "${region.index} is on atlas page ${region.page}, but only ${pages.size} " +
                        "page(s) were loaded"
                }
                TextureRegion(page, region.x, region.y, region.width, region.height)
            }

        /**
         * The same table with **no textures**: sizes, pivots and slots, and `null` for every
         * region.
         *
         * Not a test seam. A headless server and a build-time tool both legitimately need to
         * know how big a sprite is in world units - a spawn that places a unit against the
         * ground, a validator that checks a level fits its bounds - and neither has a GL context
         * to upload a page into. Before this existed the only way to reach that arithmetic was a
         * `Texture`, which meant a driver, which meant those callers either shipped a second copy
         * of the arithmetic or did without it.
         *
         * [region] throws for every index here, and says so: a caller that wanted pixels and got
         * this is a wiring mistake worth failing on.
         */
        public fun metadataOnly(regions: List<PackedRegion>): RenderAssets = build(regions) { null }

        private inline fun build(
            regions: List<PackedRegion>,
            region: (PackedRegion) -> TextureRegion?,
        ): RenderAssets {
            if (regions.isEmpty()) return EMPTY
            // Sized to the highest index, never compacted: see the KDoc on `of`.
            val size = regions.maxOf { it.index.value } + 1
            val table = arrayOfNulls<TextureRegion>(size)
            val claimed = BooleanArray(size)
            val widths = FloatArray(size)
            val heights = FloatArray(size)
            val pivotXs = FloatArray(size)
            val pivotYs = FloatArray(size)
            for (packed in regions) {
                val slot = packed.index.value
                require(!claimed[slot]) {
                    "two atlas regions claim ${packed.index}; a pack-time slot is unique by " +
                        "construction, so this is a packer bug rather than an authoring one"
                }
                claimed[slot] = true
                table[slot] = region(packed)
                widths[slot] = packed.width / packed.pixelsPerUnit
                heights[slot] = packed.height / packed.pixelsPerUnit
                pivotXs[slot] = packed.pivotX
                pivotYs[slot] = packed.pivotY
            }
            return RenderAssets(table, widths, heights, pivotXs, pivotYs)
        }
    }
}
