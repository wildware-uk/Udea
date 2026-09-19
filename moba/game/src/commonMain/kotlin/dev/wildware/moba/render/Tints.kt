package dev.wildware.moba.render

import dev.wildware.udea.render.draw.Rgba

/**
 * The same colour at a different alpha.
 *
 * ## Why this exists rather than a mutated colour
 *
 * The LibGDX renderers reached for `batch.setColor(colour.r, colour.g, colour.b, alpha)`, with a
 * comment saying why they did not assign `batch.color = colour`: a `Color` is mutable, so carrying
 * an alpha on a shared constant meant two callers fighting over one object. [Rgba] is a value class
 * over a packed `Int`, so "the same colour, fainter" is arithmetic rather than a second object -
 * this allocates nothing and there is nothing to put back.
 *
 * @param alpha `0..1`, clamped. Outside that range is a caller bug rather than a colour, but a
 *   clamp on the draw path is cheaper than a throw from inside a frame.
 */
internal fun Rgba.withAlpha(alpha: Float): Rgba {
    val channel = (alpha.coerceIn(0f, 1f) * ALPHA_STEPS + 0.5f).toInt()
    return Rgba((packed and ALPHA_CLEAR.toInt()) or channel)
}

/** 255, the number of alpha steps `Rgba` packs into its low byte. */
private const val ALPHA_STEPS = 255f

/** `0xFFFFFF00`: every channel but alpha. */
private const val ALPHA_CLEAR = 0xFFFFFF00u
