package dev.wildware.udea.agent.host

import java.awt.image.BufferedImage

/**
 * A decoded image, as four bytes per pixel in row-major order from the **top-left**.
 *
 * Its own type rather than a [BufferedImage] because the comparison must not depend on what
 * `TYPE_INT_ARGB` versus `TYPE_3BYTE_BGR` does to a channel: two PNGs that differ only in the
 * colour model `ImageIO` picked for them would otherwise compare unequal in every pixel, which
 * is the single most convincing way for a diff tool to be wrong. Everything is normalised to
 * RGBA on the way in, once.
 */
public class RgbaImage(
    /** Pixels across. */
    public val width: Int,
    /** Pixels down. */
    public val height: Int,
    /** `width * height * 4` bytes, R,G,B,A per pixel, rows top-down. */
    public val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "an image is at least 1x1, was ${width}x$height" }
        require(pixels.size == width * height * CHANNELS) {
            "expected ${width * height * CHANNELS} bytes for ${width}x$height, got ${pixels.size}"
        }
    }

    /** The channel value at [x], [y], [channel], as `0..255`. */
    public fun channel(x: Int, y: Int, channel: Int): Int =
        pixels[(y * width + x) * CHANNELS + channel].toInt() and 0xFF

    override fun toString(): String = "RgbaImage(${width}x$height)"

    public companion object {
        /** R, G, B, A. */
        public const val CHANNELS: Int = 4

        /** [image] normalised to RGBA, top-down. */
        public fun of(image: BufferedImage): RgbaImage {
            val width = image.width
            val height = image.height
            val out = ByteArray(width * height * CHANNELS)
            var index = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val argb = image.getRGB(x, y)
                    out[index++] = ((argb shr 16) and 0xFF).toByte()
                    out[index++] = ((argb shr 8) and 0xFF).toByte()
                    out[index++] = (argb and 0xFF).toByte()
                    out[index++] = ((argb ushr 24) and 0xFF).toByte()
                }
            }
            return RgbaImage(width, height, out)
        }
    }
}

/** The rectangle changed pixels fall inside, in image coordinates from the top-left. */
public class DiffBounds(
    /** Leftmost changed column. */
    public val x: Int,
    /** Topmost changed row. */
    public val y: Int,
    /** Width of the changed region. `0` when nothing changed. */
    public val w: Int,
    /** Height of the changed region. `0` when nothing changed. */
    public val h: Int,
) {
    override fun toString(): String = "DiffBounds($x, $y, ${w}x$h)"

    public companion object {
        /** Nothing changed. */
        public val EMPTY: DiffBounds = DiffBounds(0, 0, 0, 0)
    }
}

/** What comparing two images produced. */
public class DiffReport(
    /** Whether every pixel was within tolerance. */
    public val identical: Boolean,
    /** How many pixels exceeded tolerance on at least one channel. */
    public val differentPixels: Int,
    /** [differentPixels] over the pixel count, `0.0`..`1.0`. */
    public val fraction: Double,
    /** The largest per-channel delta seen anywhere, `0..255`. */
    public val maxChannelDelta: Int,
    /** The rectangle the changed pixels fall inside. */
    public val bbox: DiffBounds,
) {
    override fun toString(): String =
        "DiffReport(identical=$identical, $differentPixels px, max delta $maxChannelDelta, $bbox)"
}

/**
 * Per-pixel RGBA comparison with a tolerance. Pure CPU: no GL, no context, no window.
 *
 * ## Why per-pixel and not something cleverer
 *
 * The Phase 1 demo needs one answer - "did anything change between these two frames, and where" -
 * and per-pixel with a tolerance gives it in a form the agent can act on. SSIM and colour-space
 * distance answer a different, perceptual question that nothing in the demo asks, and both make
 * "identical" a judgement call rather than a fact.
 *
 * ## Why the tolerance exists at all
 *
 * A GPU is allowed to differ by a least significant bit between drivers and between a hidden
 * window and a visible one, and a diff that reports every one of those as a change reports every
 * comparison as a change. `tolerance = 0` is still the default, because the *capture* path is
 * supposed to be deterministic and a tolerance that is on by default would hide the day it stops
 * being.
 */
public object ImageDiff {

    /**
     * Compares [a] and [b], where a channel delta of at most [tolerance] counts as equal.
     *
     * Both images are top-down here, because both came through the same decoder. That matters:
     * framebuffer rows are bottom-up, and if capture and diff disagreed about the flip every
     * comparison would be a full-image difference that looked exactly like a real one.
     */
    public fun compare(a: RgbaImage, b: RgbaImage, tolerance: Int = 0): DiffReport {
        require(a.width == b.width && a.height == b.height) {
            "compare needs equal dimensions; got ${a.width}x${a.height} and ${b.width}x${b.height}"
        }
        require(tolerance >= 0) { "tolerance must not be negative, was $tolerance" }

        var different = 0
        var maxDelta = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1

        val left = a.pixels
        val right = b.pixels
        var index = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                var changed = false
                var channel = 0
                while (channel < RgbaImage.CHANNELS) {
                    val delta = kotlin.math.abs(
                        (left[index + channel].toInt() and 0xFF) - (right[index + channel].toInt() and 0xFF),
                    )
                    if (delta > maxDelta) maxDelta = delta
                    if (delta > tolerance) changed = true
                    channel++
                }
                if (changed) {
                    different++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
                index += RgbaImage.CHANNELS
            }
        }

        val bounds = if (maxX < 0) {
            DiffBounds.EMPTY
        } else {
            DiffBounds(minX, minY, maxX - minX + 1, maxY - minY + 1)
        }
        val total = a.width.toLong() * a.height.toLong()
        return DiffReport(
            identical = different == 0,
            differentPixels = different,
            fraction = if (total == 0L) 0.0 else different.toDouble() / total.toDouble(),
            maxChannelDelta = maxDelta,
            bbox = bounds,
        )
    }

    /**
     * The visualisation: [a] dimmed, with every changed pixel painted [HIGHLIGHT].
     *
     * Written back into the artifact store so an agent whose scalars are ambiguous - "0.3% of
     * pixels changed" could be a health bar or a rendering fault - can look at one image instead
     * of downloading two.
     */
    public fun visualise(a: RgbaImage, b: RgbaImage, tolerance: Int): BufferedImage {
        val out = BufferedImage(a.width, a.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                var changed = false
                for (channel in 0 until RgbaImage.CHANNELS) {
                    if (kotlin.math.abs(a.channel(x, y, channel) - b.channel(x, y, channel)) > tolerance) {
                        changed = true
                        break
                    }
                }
                val argb = if (changed) {
                    HIGHLIGHT
                } else {
                    val r = a.channel(x, y, 0) / DIM
                    val g = a.channel(x, y, 1) / DIM
                    val bl = a.channel(x, y, 2) / DIM
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
                out.setRGB(x, y, argb)
            }
        }
        return out
    }

    /** Opaque magenta: not a colour a game renders by accident, so a hit reads as a hit. */
    public const val HIGHLIGHT: Int = 0xFFFF00FF.toInt()

    /** How much the unchanged base is dimmed behind the highlights. */
    private const val DIM: Int = 4
}
