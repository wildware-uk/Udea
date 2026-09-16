package dev.wildware.udea.spike.kooloffscreen

import dev.wildware.udea.spike.kooloffscreen.ExpectedFrame.BACKGROUND
import dev.wildware.udea.spike.kooloffscreen.ExpectedFrame.QUAD_MAX
import dev.wildware.udea.spike.kooloffscreen.ExpectedFrame.QUAD_MIN
import dev.wildware.udea.spike.kooloffscreen.ExpectedFrame.SIZE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spike's verdict is only as good as [ExpectedFrame.mismatches], so it is exercised against
 * the frames a broken render path actually produces, not only against the one that passes.
 */
class FrameCheckTest {

    @Test
    fun `the picture as specified passes`() {
        assertEquals(emptyList(), ExpectedFrame.mismatches(paint { x, y -> expectedAt(x, y) }))
    }

    @Test
    fun `an all-zero read-back fails as a single colour`() {
        val mismatches = ExpectedFrame.mismatches(RgbaFrame(SIZE, SIZE, ByteArray(SIZE * SIZE * 4)))
        assertEquals(listOf("frame is a single colour: rgb(0,0,0)"), mismatches)
    }

    @Test
    fun `a cleared frame with no quad drawn fails on all four quadrants`() {
        val mismatches = ExpectedFrame.mismatches(paint { x, y -> if (x == 0 && y == 0) Rgb(1, 1, 1) else BACKGROUND })
        assertEquals(4, mismatches.count { it.startsWith("quad ") }, mismatches.toString())
    }

    @Test
    fun `a read-back upside down fails`() {
        val mismatches = ExpectedFrame.mismatches(paint { x, y -> expectedAt(x, SIZE - 1 - y) })
        assertTrue(mismatches.isNotEmpty(), "a vertically flipped frame passed")
    }

    @Test
    fun `a texture mirrored left to right fails`() {
        val mismatches = ExpectedFrame.mismatches(paint { x, y -> expectedAt(SIZE - 1 - x, y) })
        assertTrue(mismatches.isNotEmpty(), "a horizontally mirrored frame passed")
    }

    @Test
    fun `a frame of the wrong size fails`() {
        val mismatches = ExpectedFrame.mismatches(RgbaFrame(SIZE, SIZE - 1, ByteArray(SIZE * (SIZE - 1) * 4)))
        assertTrue(mismatches.single().startsWith("frame is 256x255"), mismatches.toString())
    }

    private fun expectedAt(x: Int, y: Int): Rgb {
        if (x !in QUAD_MIN until QUAD_MAX || y !in QUAD_MIN until QUAD_MAX) return BACKGROUND
        val cell = (QUAD_MAX - QUAD_MIN) / 2
        return ExpectedFrame.TEXELS[(y - QUAD_MIN) / cell][(x - QUAD_MIN) / cell]
    }

    private fun paint(colourAt: (Int, Int) -> Rgb): RgbaFrame {
        val pixels = ByteArray(SIZE * SIZE * 4)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val c = colourAt(x, y)
            val i = (y * SIZE + x) * 4
            pixels[i] = c.r.toByte()
            pixels[i + 1] = c.g.toByte()
            pixels[i + 2] = c.b.toByte()
            pixels[i + 3] = 0xff.toByte()
        }
        return RgbaFrame(SIZE, SIZE, pixels)
    }
}
