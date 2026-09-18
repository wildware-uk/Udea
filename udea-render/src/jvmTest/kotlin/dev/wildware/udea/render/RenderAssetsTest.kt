package dev.wildware.udea.render

import dev.wildware.udea.assets.AssetIndex
import dev.wildware.udea.render.support.AllocationProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The `AssetIndex` → region table: a bounds-checked array index that allocates nothing.
 *
 * ## Why there is no GL context here
 *
 * A `TextureRegion` constructed against a `Texture` needs one, and this test needs neither: the
 * *table* is the thing under test — the slot arithmetic, the world-size arithmetic, the pivot
 * arithmetic and the absence of allocation on the lookup path. Those are properties of arrays,
 * and testing them through a real texture upload would put the assertion behind a driver and
 * make it skip on a headless machine, which is how a gate stops gating.
 *
 * The one thing that consequently is *not* covered here is that `TextureRegion(page, x, y, w, h)`
 * cuts the rectangle a packer meant. That belongs with the packer, which owns the coordinates.
 */
class RenderAssetsTest {

    @Test
    fun `an empty pack yields a table with no slots`() {
        val assets = RenderAssets.of(emptyList(), emptyList())
        assertEquals(0, assets.size)
        assertNull(assets.regionOrNull(AssetIndex(0)))
    }

    @Test
    fun `the table is sized to the highest index, not to the region count`() {
        // Two sprites in a graph whose other assets are sounds and blueprints, so their slots
        // are 0 and 900. Compacting them would break the property an AssetIndex exists for.
        val assets = RenderAssets.metadataOnly(listOf(region(index = 0), region(index = 900)))
        assertEquals(901, assets.size)
        assertEquals(2f, assets.worldWidth(AssetIndex(900)), 1e-6f)
    }

    @Test
    fun `a duplicate index is refused as a packer bug`() {
        val error = assertThrows<IllegalArgumentException> {
            RenderAssets.metadataOnly(listOf(region(index = 3), region(index = 3)))
        }
        assertTrue(error.message!!.contains("claim"), error.message)
    }

    @Test
    fun `a region on a page nobody loaded is refused rather than drawn as nothing`() {
        val error = assertThrows<IllegalArgumentException> {
            RenderAssets.of(emptyList(), listOf(region(index = 0)))
        }
        assertTrue(error.message!!.contains("page"), error.message)
    }

    @Test
    fun `metadataOnly answers sizes and refuses pixels`() {
        val assets = arithmeticTable()
        assertEquals(2f, assets.worldWidth(AssetIndex(1)), 1e-6f)
        assertNull(assets.regionOrNull(AssetIndex(1)))
        assertThrows<IllegalArgumentException> { assets.region(AssetIndex(1)) }
    }

    @Test
    fun `an index past the end is refused rather than read out of bounds`() {
        val assets = RenderAssets.EMPTY
        assertNull(assets.regionOrNull(AssetIndex(7)))
        val error = assertThrows<IllegalArgumentException> { assets.region(AssetIndex(7)) }
        assertTrue(error.message!!.contains("7"), error.message)
    }

    @Test
    fun `world size comes from pack-time pixels per unit, not from a renderer constant`() {
        val assets = arithmeticTable()
        // 100px wide at 50 px/unit is two world units. The old path multiplied by a hardcoded
        // WORLD_SCALE of 0.1 and no authored value could change it.
        assertEquals(2f, assets.worldWidth(AssetIndex(1)), 1e-6f)
        assertEquals(1f, assets.worldHeight(AssetIndex(1)), 1e-6f)
    }

    @Test
    fun `worldQuad places the region by its pack-time pivot`() {
        val assets = arithmeticTable()
        val quad = FloatArray(RenderAssets.QUAD_SIZE)
        // pivot (0.5, 0) is a foot-planted sprite: centred horizontally, sitting on its origin.
        assets.worldQuad(AssetIndex(1), worldX = 10f, worldY = 4f, into = quad)
        assertEquals(9f, quad[0], 1e-6f)
        assertEquals(4f, quad[1], 1e-6f)
        assertEquals(2f, quad[2], 1e-6f)
        assertEquals(1f, quad[3], 1e-6f)
    }

    @Test
    fun `worldQuad refuses an array it would overrun`() {
        val assets = arithmeticTable()
        assertThrows<IllegalArgumentException> {
            assets.worldQuad(AssetIndex(1), 0f, 0f, FloatArray(RenderAssets.QUAD_SIZE - 1))
        }
    }

    /**
     * The property the hot path is for: resolving and placing a sprite allocates zero bytes.
     *
     * The path this replaces — `AssetRefImpl.value`, a `by lazy` hash lookup into a global map —
     * allocated on every miss and paid a `String.hashCode` on every hit, once per entity per
     * frame. Zero is asserted rather than "small": a lookup that allocates *anything* per entity
     * per frame is a GC pause landing inside a frame at a few hundred entities.
     */
    @Test
    fun `resolving and placing allocates nothing`() {
        assumeTrue(AllocationProbe.isSupported, "thread allocation counters unavailable")
        val assets = arithmeticTable()
        val quad = FloatArray(RenderAssets.QUAD_SIZE)
        val index = AssetIndex(1)
        val bytes = AllocationProbe.bytesAllocated {
            repeat(1_000) {
                assets.worldWidth(index)
                assets.worldHeight(index)
                assets.pivotX(index)
                assets.worldQuad(index, 1f, 2f, quad)
            }
        }
        assertEquals(0L, bytes, "one thousand lookups allocated $bytes bytes")
        assertNotNull(quad)
    }

    /**
     * The arithmetic half of a real table, built the way a headless caller builds one.
     *
     * `RenderAssets.metadataOnly` and not a reflective poke at the private constructor: the
     * factory exists for callers that need world sizes without a GL context, and using it here
     * means this test drives shipped code rather than a back door into it.
     */
    private fun arithmeticTable(): RenderAssets = RenderAssets.metadataOnly(
        listOf(
            PackedRegion(
                index = AssetIndex(1),
                page = 0,
                x = 0,
                y = 0,
                width = 100,
                height = 50,
                pivotX = 0.5f,
                pivotY = 0f,
                pixelsPerUnit = 50f,
            ),
        ),
    )

    private fun region(index: Int) = PackedRegion(
        index = AssetIndex(index),
        page = 0,
        x = 0,
        y = 0,
        width = 100,
        height = 50,
        pixelsPerUnit = 50f,
    )
}
