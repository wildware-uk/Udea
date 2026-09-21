package dev.wildware.udea.render.sky

import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResource
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture

/**
 * Records a [Sky]'s background into a batch, first thing in a frame (issue #267).
 *
 * Called by the pipeline, never by a game: once for the capturable frame before any `RenderSystem`
 * draws, and once for each editor Scene view before the world is drawn into it again. Either way it
 * is the first draw in the record, so it is under everything, and it is drawn in the target's own
 * pixels, so it fills the frame whatever the camera is doing.
 *
 * ## Nothing, a fill, or one texture
 *
 * [SkyBackground.None] records nothing at all - not a black quad over the black clear colour, which
 * would look the same and would still be a change to every frame of every game that never asked for
 * a sky. [SkyBackground.Solid] is the batch's own white texel, tinted. [SkyBackground.Gradient] is a
 * texture one texel wide and [GRADIENT_ROWS] tall, made once per gradient and kept until the game
 * sets a different one, so a frame with a sky draws one quad and allocates nothing.
 */
internal class SkyPainter(private val sky: Sky) : RenderResource {

    /** The gradient [gradientRegion] was made for, or `null` when none has been made. */
    private var gradientFor: SkyBackground.Gradient? = null

    private var gradientRegion: SpriteRegion? = null

    /** Records the sky over the whole of [target]. Render thread only. */
    fun paint(batch: SpriteBatch2D, target: OffscreenTarget) {
        // Read once: a game may set the sky from another thread, and the frame draws one value.
        val background = sky.background
        if (background == SkyBackground.None) return
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        batch.beginPixels()
        try {
            when (background) {
                SkyBackground.None -> Unit
                is SkyBackground.Solid -> batch.fill(0f, 0f, width, height, background.colour)
                is SkyBackground.Gradient -> batch.draw(regionFor(background), 0f, 0f, width, height, Rgba.WHITE)
            }
        } finally {
            batch.end()
        }
    }

    /**
     * The texture for [gradient], made the first time it is asked for. The one it replaces is
     * released here, on the render thread, before this frame's record is drawn: the last frame
     * that drew it has already been drawn, and this frame's record names the new one.
     */
    private fun regionFor(gradient: SkyBackground.Gradient): SpriteRegion {
        val current = gradientRegion
        if (current != null && gradient == gradientFor) return current
        current?.texture?.release()
        val made = SpriteRegion(SpriteTexture.fromRgba(1, GRADIENT_ROWS, gradientRows(gradient), GRADIENT_NAME))
        gradientRegion = made
        gradientFor = gradient
        return made
    }

    override fun release() {
        gradientRegion?.texture?.release()
        gradientRegion = null
        gradientFor = null
    }

    override fun toString(): String = "SkyPainter($sky)"

    internal companion object {

        /**
         * Rows in a gradient's texture. One per level a channel can take: a blend across the whole
         * range from 0 to 255 then steps one level a row, which is as smooth as eight bits a
         * channel can show, and any narrower blend steps less.
         */
        const val GRADIENT_ROWS: Int = 256

        private const val GRADIENT_NAME = "udea-sky-gradient"

        private const val BYTES_PER_TEXEL = 4

        /** [gradient]'s texels, top row first: [SkyBackground.Gradient.top] to its bottom colour. */
        fun gradientRows(gradient: SkyBackground.Gradient): ByteArray {
            val rgba = ByteArray(GRADIENT_ROWS * BYTES_PER_TEXEL)
            val top = gradient.top
            val bottom = gradient.bottom
            for (row in 0 until GRADIENT_ROWS) {
                val t = row / (GRADIENT_ROWS - 1f)
                val colour = Rgba.of(
                    top.r + (bottom.r - top.r) * t,
                    top.g + (bottom.g - top.g) * t,
                    top.b + (bottom.b - top.b) * t,
                    top.a + (bottom.a - top.a) * t,
                )
                val at = row * BYTES_PER_TEXEL
                rgba[at] = (colour.packed ushr 24).toByte()
                rgba[at + 1] = (colour.packed ushr 16).toByte()
                rgba[at + 2] = (colour.packed ushr 8).toByte()
                rgba[at + 3] = colour.packed.toByte()
            }
            return rgba
        }
    }
}
