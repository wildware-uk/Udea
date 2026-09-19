package com.badlogic.gdx.math

/**
 * A stand-in for LibGDX's `MathUtils`, vendored the same way as the scene2d `Stage` beside it.
 *
 * It is LibGDX but not scene2d, so it reaches the general `com/badlogic/` entry of the banned table
 * rather than the scene2d one - which is what shows the scene2d entry is matched first and gives
 * its own reason.
 */
internal object MathUtils {
    fun clamp(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max)
}
