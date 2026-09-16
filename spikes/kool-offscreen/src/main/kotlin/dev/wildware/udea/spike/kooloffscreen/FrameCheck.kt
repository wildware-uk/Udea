package dev.wildware.udea.spike.kooloffscreen

/**
 * An RGBA8 frame, rows stored top row first, as the PNG shows it.
 */
internal class RgbaFrame(val width: Int, val height: Int, val pixels: ByteArray) {
    fun rgb(x: Int, y: Int): Rgb {
        val i = (y * width + x) * 4
        return Rgb(pixels[i].toInt() and 0xff, pixels[i + 1].toInt() and 0xff, pixels[i + 2].toInt() and 0xff)
    }
}

internal data class Rgb(val r: Int, val g: Int, val b: Int) {
    fun near(other: Rgb, tolerance: Int): Boolean =
        kotlin.math.abs(r - other.r) <= tolerance &&
            kotlin.math.abs(g - other.g) <= tolerance &&
            kotlin.math.abs(b - other.b) <= tolerance

    override fun toString() = "rgb($r,$g,$b)"
}

/**
 * The picture the spike draws, and the only definition of what "it rendered" means.
 *
 * A square frame cleared to [BACKGROUND], with a quad over its middle half textured by a 2x2
 * nearest-filtered texture: [TOP_LEFT] and [TOP_RIGHT] in the top texel row, [BOTTOM_LEFT] and
 * [BOTTOM_RIGHT] in the bottom one. Every channel is 0 or 255 so an sRGB/linear conversion
 * anywhere in the pipeline cannot move a value, and the four quadrant colours differ from each
 * other and from the background so a missing draw, a flipped read-back and a mirrored texture
 * each change a checked pixel.
 */
internal object ExpectedFrame {
    const val SIZE = 256
    val BACKGROUND = Rgb(255, 0, 255)
    val TOP_LEFT = Rgb(255, 0, 0)
    val TOP_RIGHT = Rgb(0, 255, 0)
    val BOTTOM_LEFT = Rgb(0, 0, 255)
    val BOTTOM_RIGHT = Rgb(255, 255, 255)

    /** The quad covers `[QUAD_MIN, QUAD_MAX)` on both axes. */
    const val QUAD_MIN = SIZE / 4
    const val QUAD_MAX = SIZE * 3 / 4

    /** Rows of the texture, top row first. */
    val TEXELS = listOf(listOf(TOP_LEFT, TOP_RIGHT), listOf(BOTTOM_LEFT, BOTTOM_RIGHT))

    private const val TOLERANCE = 8

    /**
     * Every way [frame] differs from the expected picture; empty means it rendered.
     *
     * Checks the centre of each quadrant and each background corner as a block rather than as a
     * pixel, inset from every edge so antialiasing and half-texel placement cannot decide it.
     */
    fun mismatches(frame: RgbaFrame): List<String> {
        if (frame.width != SIZE || frame.height != SIZE || frame.pixels.size != SIZE * SIZE * 4) {
            return listOf("frame is ${frame.width}x${frame.height} with ${frame.pixels.size} bytes, expected ${SIZE}x$SIZE RGBA")
        }
        val distinct = HashSet<Rgb>()
        for (y in 0 until SIZE) for (x in 0 until SIZE) distinct += frame.rgb(x, y)
        if (distinct.size == 1) return listOf("frame is a single colour: ${distinct.single()}")

        val cell = (QUAD_MAX - QUAD_MIN) / 2
        val inset = cell / 4
        val blocks = buildList {
            add(Block("background top-left", 0, 0, BACKGROUND))
            add(Block("background top-right", SIZE - cell, 0, BACKGROUND))
            add(Block("background bottom-left", 0, SIZE - cell, BACKGROUND))
            add(Block("background bottom-right", SIZE - cell, SIZE - cell, BACKGROUND))
            add(Block("quad top-left", QUAD_MIN, QUAD_MIN, TOP_LEFT))
            add(Block("quad top-right", QUAD_MIN + cell, QUAD_MIN, TOP_RIGHT))
            add(Block("quad bottom-left", QUAD_MIN, QUAD_MIN + cell, BOTTOM_LEFT))
            add(Block("quad bottom-right", QUAD_MIN + cell, QUAD_MIN + cell, BOTTOM_RIGHT))
        }
        return blocks.mapNotNull { block ->
            var wrong = 0
            var first: String? = null
            for (y in block.y + inset until block.y + cell - inset) {
                for (x in block.x + inset until block.x + cell - inset) {
                    val seen = frame.rgb(x, y)
                    if (!seen.near(block.expected, TOLERANCE)) {
                        wrong++
                        if (first == null) first = "($x,$y) is $seen"
                    }
                }
            }
            if (wrong == 0) null else "${block.name}: $wrong pixels differ from ${block.expected}, first $first"
        }
    }

    private class Block(val name: String, val x: Int, val y: Int, val expected: Rgb)
}
