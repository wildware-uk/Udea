package dev.wildware.udea.render.camera

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/**
 * Fraction of the remaining distance a camera closes in a frame of [dtSeconds], when it closes half
 * of it every [halfLife] seconds. `1` - no easing - for a zero half-life or a zero-length frame.
 *
 * `1 - 2^(-dt / halfLife)`, which is the frame-rate-independent form: halving the frame time halves
 * the step, so two frames of a 120Hz display move a camera exactly as far as one frame of a 60Hz
 * one. A fixed per-frame fraction does not, and is why cameras tuned on one machine feel sluggish on
 * another. Shared by [CameraRig], [ThirdPersonRig] and [IsometricRig], which ease the same way.
 */
internal fun halfLifeStep(dtSeconds: Float, halfLife: Float): Float {
    if (halfLife <= 0f || dtSeconds <= 0f) return 1f
    return 1f - exp(-LN_2 * dtSeconds / halfLife)
}

/** [degrees] folded into (-180, 180], so an angle turned round and round keeps its precision. */
internal fun wrapDegrees(degrees: Float): Float {
    var folded = degrees % FULL_TURN
    if (folded <= -HALF_TURN) folded += FULL_TURN
    if (folded > HALF_TURN) folded -= FULL_TURN
    return folded
}

/** [degrees] as radians, for the trigonometry every rig in this package does. */
internal fun radians(degrees: Float): Float = (degrees * PI / HALF_TURN).toFloat()

private val LN_2: Float = ln(2f)

private const val HALF_TURN: Float = 180f
private const val FULL_TURN: Float = 360f
