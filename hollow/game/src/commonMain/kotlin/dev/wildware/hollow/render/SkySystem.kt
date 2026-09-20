package dev.wildware.hollow.render

import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture

/**
 * The sky: a gradient from a deep blue overhead to a pale haze at the horizon, drawn full frame
 * before the 3D pass, which clears to transparent and so shows it wherever no model is drawn.
 *
 * A gradient in screen space rather than a sky dome: `udea-render` draws no sky of its own, and the
 * camera H1 uses is fixed (issue #249), so the horizon sits in one place in the frame. A camera
 * that tilts - the third-person rig of issue #250 - would want the gradient tied to the view.
 *
 * The texture is one pixel wide and [ROWS] tall, made once at construction and owned by the
 * pipeline, so a frame draws one quad and allocates nothing.
 */
internal class SkySystem(private val resources: RenderResources) : RenderSystem {

    private val sky: SpriteRegion = SpriteRegion(resources.own(gradient()))

    override fun render(target: OffscreenTarget, alpha: Float) {
        val batch = resources.batch
        batch.beginPixels()
        try {
            batch.draw(sky, 0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.WHITE)
        } finally {
            batch.end()
        }
    }

    override fun toString(): String = "SkySystem"

    internal companion object {
        /** Rows in the gradient: finer than a frame's bands can show at 8 bits a channel. */
        const val ROWS: Int = 256

        /** Straight up, sRGB. */
        val ZENITH: FloatArray = floatArrayOf(0.26f, 0.46f, 0.76f)

        /** At the horizon, sRGB: a warm haze. */
        val HORIZON: FloatArray = floatArrayOf(0.82f, 0.87f, 0.86f)

        /**
         * How far down the frame the haze is thickest, as a fraction of its height: where the far
         * edge of the ground meets the sky from [HollowScene]'s camera. Below it the sky is only
         * seen between trunks, and stays haze.
         */
        const val HORIZON_AT: Float = 0.4f

        /** The gradient, top row first: [ZENITH] at the top, easing into [HORIZON] at [HORIZON_AT]. */
        fun gradient(): SpriteTexture {
            val rgba = ByteArray(ROWS * 4)
            for (row in 0 until ROWS) {
                val t = minOf(1f, row / (ROWS - 1f) / HORIZON_AT)
                // Eased so most of the sky is blue and the haze gathers near the horizon.
                val haze = t * t
                for (channel in 0 until 3) {
                    val value = ZENITH[channel] + (HORIZON[channel] - ZENITH[channel]) * haze
                    rgba[row * 4 + channel] = (value * 255f + 0.5f).toInt().toByte()
                }
                rgba[row * 4 + 3] = -1
            }
            return SpriteTexture.fromRgba(1, ROWS, rgba, "hollow-sky")
        }
    }
}
