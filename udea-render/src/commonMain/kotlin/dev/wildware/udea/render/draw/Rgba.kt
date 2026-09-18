package dev.wildware.udea.render.draw

import kotlin.jvm.JvmInline

/**
 * A colour, packed `0xRRGGBBAA`.
 *
 * A value class rather than a colour object because a tint is passed per sprite per frame, and an
 * object per draw is presentation-thread garbage sixty times a second - the reason the agent
 * overlay's canvas already took packed `Int`s. A value class rather than a bare `Int` because a
 * colour is a domain concept, and an `Int` that is sometimes a colour and sometimes a count is how
 * a tint ends up passed where a draw order was meant.
 */
@JvmInline
public value class Rgba(public val packed: Int) {

    /** Red, `0..1`. */
    public val r: Float get() = ((packed ushr 24) and 0xFF) / 255f

    /** Green, `0..1`. */
    public val g: Float get() = ((packed ushr 16) and 0xFF) / 255f

    /** Blue, `0..1`. */
    public val b: Float get() = ((packed ushr 8) and 0xFF) / 255f

    /** Alpha, `0..1`. */
    public val a: Float get() = (packed and 0xFF) / 255f

    override fun toString(): String = "Rgba(0x${packed.toUInt().toString(16).padStart(8, '0')})"

    public companion object {

        /** Opaque white: multiplying by it leaves a texture as it is. */
        public val WHITE: Rgba = Rgba(0xFFFFFFFF.toInt())

        /** Opaque black. */
        public val BLACK: Rgba = Rgba(0x000000FF)

        /**
         * Packs four channels in `0..1`, each clamped and rounded to the nearest of 256 steps.
         */
        public fun of(r: Float, g: Float, b: Float, a: Float = 1f): Rgba =
            Rgba((channel(r) shl 24) or (channel(g) shl 16) or (channel(b) shl 8) or channel(a))

        private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
}
