package dev.wildware.moba.render

import dev.wildware.udea.render.draw.SpriteTexture

/**
 * An RGBA8888 image the game paints shapes into before uploading it once.
 *
 * ## What it replaces
 *
 * LibGDX's `Pixmap`, in the two places this game generated art rather than loading it: the
 * footprint, ring and chevron `WorldMarkers` draws under a unit, and `BackgroundRenderSystem`'s
 * seamless grass tile. Both built an image on the CPU at construction and uploaded it once, and
 * both still do - only the canvas changed, because `Pixmap` was a LibGDX native buffer and left
 * with issue #211.
 *
 * ## Why the shapes are anti-aliased here and were not before
 *
 * `Pixmap.fillCircle` is a hard-edged raster - it decides per texel, in or out - and the ragged
 * result was hidden by `Texture.TextureFilter.Linear`, which LibGDX let a texture choose per
 * texture and which smoothed a 64px circle drawn at roughly a third of that size.
 * `SpriteTexture` samples `nearest().clamped()` for every texture it makes, because pixel art is
 * what the rest of this game draws and a linear filter turns a 100px character frame scaled to 16
 * world units into mush. So the smoothing moves into the image: [fillCircle] and [fillTriangle]
 * sample [SUBSAMPLES] by [SUBSAMPLES] points per texel and write the coverage as alpha, which is a
 * softer edge than a linear filter gave and does not cost the character sheets their crispness.
 *
 * ## Origin
 *
 * `y = 0` is the **top** row, the convention every image format and [SpriteTexture.fromRgba] use.
 */
internal class PixelCanvas(val width: Int, val height: Int) {

    init {
        require(width > 0 && height > 0) { "a canvas cannot be ${width}x$height" }
    }

    /** `width * height * 4` bytes, RGBA, top row first. Transparent black to begin with. */
    private val pixels = ByteArray(width * height * BYTES_PER_PIXEL)

    /** Writes one texel, replacing whatever was there. */
    fun set(x: Int, y: Int, red: Int, green: Int, blue: Int, alpha: Int = OPAQUE) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val at = (y * width + x) * BYTES_PER_PIXEL
        pixels[at] = red.toByte()
        pixels[at + 1] = green.toByte()
        pixels[at + 2] = blue.toByte()
        pixels[at + 3] = alpha.toByte()
    }

    /**
     * A filled circle of radius [radius] about ([centreX], [centreY]), anti-aliased.
     *
     * Composited source-over onto what is already there, so two overlapping shapes read as one and
     * the softened edge of the second does not punch through the first.
     */
    fun fillCircle(centreX: Float, centreY: Float, radius: Float, red: Int, green: Int, blue: Int) {
        val squared = radius * radius
        forEachCovered(
            left = centreX - radius, top = centreY - radius,
            right = centreX + radius, bottom = centreY + radius,
        ) { x, y ->
            val dx = x - centreX
            val dy = y - centreY
            dx * dx + dy * dy <= squared
        }.paint(red, green, blue)
    }

    /**
     * Clears a filled circle back to transparent, anti-aliased at its edge.
     *
     * The ring is a disc with a smaller disc taken out of it, and taking it out is not the same as
     * drawing a transparent one: `set` replaces, so the edge texels of the hole have to keep the
     * coverage the outer disc gave them minus the coverage of the inner one.
     */
    fun eraseCircle(centreX: Float, centreY: Float, radius: Float) {
        val squared = radius * radius
        forEachCovered(
            left = centreX - radius, top = centreY - radius,
            right = centreX + radius, bottom = centreY + radius,
        ) { x, y ->
            val dx = x - centreX
            val dy = y - centreY
            dx * dx + dy * dy <= squared
        }.erase()
    }

    /** A filled triangle through the three points, anti-aliased. */
    @Suppress("LongParameterList")
    fun fillTriangle(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        red: Int, green: Int, blue: Int,
    ) {
        forEachCovered(
            left = minOf(x0, x1, x2), top = minOf(y0, y1, y2),
            right = maxOf(x0, x1, x2), bottom = maxOf(y0, y1, y2),
        ) { x, y ->
            // Same sign on all three edge functions means the point is inside, whichever way
            // round the vertices were given.
            val a = edge(x0, y0, x1, y1, x, y)
            val b = edge(x1, y1, x2, y2, x, y)
            val c = edge(x2, y2, x0, y0, x, y)
            (a >= 0f && b >= 0f && c >= 0f) || (a <= 0f && b <= 0f && c <= 0f)
        }.paint(red, green, blue)
    }

    /** Uploads as a texture named [name]. The caller hands it to `RenderResources.own`. */
    fun toTexture(name: String): SpriteTexture = SpriteTexture.fromRgba(width, height, pixels, name)

    /**
     * Per-texel coverage over a rectangle, as a fraction in `0..1`.
     *
     * Returned as an object with [Coverage.paint] and [Coverage.erase] on it rather than as a
     * `FloatArray` a caller interprets, so the two things a shape can do to the canvas are the two
     * methods, and a caller cannot get the blend backwards.
     */
    private inline fun forEachCovered(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        inside: (Float, Float) -> Boolean,
    ): Coverage {
        val x0 = maxOf(0, kotlin.math.floor(left).toInt())
        val y0 = maxOf(0, kotlin.math.floor(top).toInt())
        val x1 = minOf(width - 1, kotlin.math.ceil(right).toInt())
        val y1 = minOf(height - 1, kotlin.math.ceil(bottom).toInt())
        val coverage = Coverage(this, x0, y0, x1, y1)
        if (x1 < x0 || y1 < y0) return coverage
        val step = 1f / SUBSAMPLES
        val first = step / 2f
        for (y in y0..y1) {
            for (x in x0..x1) {
                var hits = 0
                for (sy in 0 until SUBSAMPLES) {
                    for (sx in 0 until SUBSAMPLES) {
                        if (inside(x + first + sx * step, y + first + sy * step)) hits++
                    }
                }
                coverage.set(x, y, hits.toFloat() / (SUBSAMPLES * SUBSAMPLES))
            }
        }
        return coverage
    }

    /** One shape's per-texel coverage, and the two ways it can be applied. */
    private class Coverage(
        private val canvas: PixelCanvas,
        private val x0: Int,
        private val y0: Int,
        private val x1: Int,
        private val y1: Int,
    ) {
        private val spanWidth = if (x1 >= x0) x1 - x0 + 1 else 0
        private val values = FloatArray(spanWidth * (if (y1 >= y0) y1 - y0 + 1 else 0))

        fun set(x: Int, y: Int, value: Float) {
            values[(y - y0) * spanWidth + (x - x0)] = value
        }

        /** Source-over: the shape's colour at its coverage, composited onto what is there. */
        fun paint(red: Int, green: Int, blue: Int) {
            forEach { x, y, coverage ->
                val at = (y * canvas.width + x) * BYTES_PER_PIXEL
                val under = (canvas.pixels[at + 3].toInt() and 0xFF) / 255f
                val out = coverage + under * (1f - coverage)
                if (out <= 0f) return@forEach
                canvas.set(
                    x, y,
                    blend(red, canvas.pixels[at], coverage, under, out),
                    blend(green, canvas.pixels[at + 1], coverage, under, out),
                    blend(blue, canvas.pixels[at + 2], coverage, under, out),
                    (out * 255f + 0.5f).toInt().coerceIn(0, OPAQUE),
                )
            }
        }

        /** Takes the shape's coverage back out of the alpha already there. */
        fun erase() {
            forEach { x, y, coverage ->
                val at = (y * canvas.width + x) * BYTES_PER_PIXEL
                val under = (canvas.pixels[at + 3].toInt() and 0xFF) / 255f
                val out = (under * (1f - coverage) * 255f + 0.5f).toInt().coerceIn(0, OPAQUE)
                canvas.pixels[at + 3] = out.toByte()
            }
        }

        private inline fun forEach(block: (Int, Int, Float) -> Unit) {
            if (spanWidth == 0) return
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val coverage = values[(y - y0) * spanWidth + (x - x0)]
                    if (coverage > 0f) block(x, y, coverage)
                }
            }
        }

        private fun blend(source: Int, destination: Byte, coverage: Float, under: Float, out: Float): Int {
            val below = (destination.toInt() and 0xFF).toFloat()
            return ((source * coverage + below * under * (1f - coverage)) / out + 0.5f)
                .toInt().coerceIn(0, OPAQUE)
        }
    }

    private companion object {

        const val BYTES_PER_PIXEL = 4
        const val OPAQUE = 0xFF

        /**
         * Samples per texel per axis, so sixteen per texel.
         *
         * Enough that a 64px circle's edge reads as smooth at the third of that size it is drawn
         * at, and small enough that building three shapes plus a 256-square background tile is
         * still tens of milliseconds once, before the first frame.
         */
        const val SUBSAMPLES = 4

        /** Twice the signed area of the triangle `(ax, ay), (bx, by), (x, y)`. */
        fun edge(ax: Float, ay: Float, bx: Float, by: Float, x: Float, y: Float): Float =
            (bx - ax) * (y - ay) - (by - ay) * (x - ax)
    }
}
