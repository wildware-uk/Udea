package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.spatial.Transform3D

/**
 * A 3D pose a renderer draws at: a `Transform3D`'s nine numbers. Reused, never returned fresh, for
 * the reason [Pose] is.
 */
internal class Pose3D {
    var x = 0f
    var y = 0f
    var z = 0f
    var rotationX = 0f
    var rotationY = 0f

    /** The heading: radians about Z. */
    var rotationZ = 0f
    var scaleX = 1f
    var scaleY = 1f
    var scaleZ = 1f

    @Suppress("LongParameterList") // A transform, field for field.
    fun set(x: Float, y: Float, z: Float, rotationX: Float, rotationY: Float, rotationZ: Float, scaleX: Float, scaleY: Float, scaleZ: Float) {
        this.x = x
        this.y = y
        this.z = z
        this.rotationX = rotationX
        this.rotationY = rotationY
        this.rotationZ = rotationZ
        this.scaleX = scaleX
        this.scaleY = scaleY
        this.scaleZ = scaleZ
    }

    override fun toString(): String = "Pose3D(($x, $y, $z) rot=($rotationX, $rotationY, $rotationZ) scale=($scaleX, $scaleY, $scaleZ))"
}

/**
 * Where a `Transform3D` is drawn this frame: between where the last two ticks left it, at the
 * render alpha (issue #246). [Interpolator]'s counterpart for 3D, with its rules.
 *
 * Position is lerped with [Interpolator.lerp], so alpha 1 is the current transform to the bit, and
 * the heading with [Interpolator.lerpAngle], along the short arc. Pitch, roll and scale are drawn
 * as they stand ([Interp3D] says why).
 *
 * ## When it does not interpolate
 *
 * It draws the transform as it stands, in three cases:
 *
 * - the entity has no [Interp3D] yet: it has not lived through a tick end, so there is nothing to
 *   draw from;
 * - the clock is not one tick ahead of [PoseHistory.lastTick]: a restore or a rewind, for the
 *   reason [Interpolator] gives;
 * - the transform is not where the last tick left it: something moved it between ticks, outside
 *   the barrier - an editor gizmo while paused - and drawing a lerp would lag the handle.
 */
internal class Interpolator3D(
    private val clock: SimClock,
    private val history: PoseHistory,
) {

    /** True when the world's tick sequence broke since the last recorded pose. */
    val isRestoreFrame: Boolean
        get() = clock.tick != history.lastTick + 1L

    /**
     * Writes the pose to draw [entity] at into [into].
     *
     * @param alpha the loop's interpolation alpha, in `[0, 1]`.
     * @return `false` if [entity] has no `Transform3D` and nothing was written.
     */
    fun interpolate(world: World, entity: Entity, alpha: Float, into: Pose3D): Boolean {
        with(world) {
            val t = entity.getOrNull(Transform3D) ?: return false
            into.set(t.x, t.y, t.z, t.rotationX, t.rotationY, t.rotationZ, t.scaleX, t.scaleY, t.scaleZ)
            val interp = entity.getOrNull(Interp3D) ?: return true
            if (isRestoreFrame || movedBetweenTicks(t, interp)) return true
            into.x = Interpolator.lerp(interp.prevX, t.x, alpha)
            into.y = Interpolator.lerp(interp.prevY, t.y, alpha)
            into.z = Interpolator.lerp(interp.prevZ, t.z, alpha)
            into.rotationZ = Interpolator.lerpAngle(interp.prevHeading, t.rotationZ, alpha)
            return true
        }
    }

    private fun movedBetweenTicks(t: Transform3D, interp: Interp3D): Boolean =
        t.x != interp.lastX || t.y != interp.lastY || t.z != interp.lastZ || t.rotationZ != interp.lastHeading
}
