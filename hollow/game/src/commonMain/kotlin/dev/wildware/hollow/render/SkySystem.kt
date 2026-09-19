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
        val ZENITH: FloatArray = floatArrayOf(0.30f, 0.50f, 0.78f)

        /** At the horizon, sRGB: a warm haze. */
        val HORIZON: FloatArray = floatArrayOf(0.80f, 0.86f, 0.88f)

        /** The gradient, top row first: [ZENITH] at the top, easing into [HORIZON] at the bottom. */
        fun gradient(): SpriteTexture {
            val rgba = ByteArray(ROWS * 4)
            for (row in 0 until ROWS) {
                val t = row / (ROWS - 1f)
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
