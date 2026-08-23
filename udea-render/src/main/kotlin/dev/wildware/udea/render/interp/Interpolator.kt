package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.physics.PhysicsBody

/**
 * A pose a renderer draws at. Reused, never returned fresh.
 *
 * A mutable out-parameter rather than a returned value because [Interpolator.interpolate] is
 * called once per drawn entity per frame — several thousand times a second in a MOBA team
 * fight — and a per-entity allocation there is garbage generated in the middle of drawing.
 * `RenderAllocationTest` is what keeps that claim honest.
 */
public class Pose(
    public var x: Float = 0f,
    public var y: Float = 0f,
    /** Radians. */
    public var angle: Float = 0f,
) {
    override fun toString(): String = "Pose(($x, $y) angle=$angle)"
}

/**
 * The one place a rendered position is computed. Every renderer that draws a body uses it.
 *
 * ## Why it is one helper and not four lerps
 *
 * Sprites, animations, particles and lights all draw at the same place and would each need the
 * same three rules: lerp position, lerp rotation on the shortest arc, and do neither when the
 * pose is not a pose the entity travelled through. Four copies of that is three chances for one
 * of them to drift — a sprite at the interpolated position with its light at the simulated one
 * is a light that lags its own lamp by up to a tick, which reads as an art bug.
 *
 * ## When it does not interpolate
 *
 * `alpha` is treated as `1` — draw exactly where the simulation says — in three cases:
 *
 * - [Interp.snap] is set, because a `Teleport` moved the body discontinuously this tick. A
 *   lerp there sweeps the entity smoothly over ground it never crossed.
 * - the clock is not one tick ahead of [InterpSnapshotSystem.lastTick], which is what a
 *   snapshot restore or a rewind looks like from here: the recorded previous pose belongs to a
 *   world that has been replaced, so lerping from it draws one frame of the entity flying in
 *   from wherever it used to be sixty seconds ago. Agents look at screenshots constantly and
 *   would read that frame as a spawn.
 * - the entity has no [Interp] at all, so there is nothing to interpolate from.
 *
 * The first is per-entity, the second is per-world, and both collapse into the same answer, so
 * a caller never has to know which applied.
 */
public class Interpolator(
    private val clock: SimClock,
    private val history: PoseHistory,
) {

    /**
     * True when the world's tick sequence broke since the last recorded pose.
     *
     * Read once per frame by the renderers rather than per entity: it is a property of the
     * world, and asking per entity would be the same comparison thousands of times.
     */
    public val isRestoreFrame: Boolean
        get() = clock.tick != history.lastTick + 1L

    /**
     * Writes the pose to draw [entity] at into [into].
     *
     * @param alpha the loop's interpolation alpha, in `[0, 1]`.
     * @return `false` if [entity] has no [PhysicsBody] and nothing was written, so a caller can
     *   skip it without a second component lookup.
     */
    public fun interpolate(world: World, entity: Entity, alpha: Float, into: Pose): Boolean {
        with(world) {
            val body = entity.getOrNull(PhysicsBody) ?: return false
            val interp = entity.getOrNull(Interp)

            if (interp == null || interp.snap || isRestoreFrame) {
                into.x = body.x
                into.y = body.y
                into.angle = body.angle
                return true
            }

            into.x = lerp(interp.prevX, body.x, alpha)
            into.y = lerp(interp.prevY, body.y, alpha)
            into.angle = lerpAngle(interp.prevAngle, body.angle, alpha)
            return true
        }
    }

    public companion object {

        /**
         * Linear interpolation that reproduces both endpoints exactly.
         *
         * `from + (to - from) * t` is the form that does *not*: at `t == 1` it yields
         * `from + (to - from)`, which for two floats of different magnitude is off by an ulp
         * or two. That matters here because "alpha == 1 reproduces the current transform to
         * the bit" is a property this engine tests — an agent comparing a screenshot against
         * a simulated position needs the two to agree exactly, not nearly.
         */
        public fun lerp(from: Float, to: Float, t: Float): Float =
            from * (1f - t) + to * t

        /**
         * Interpolates an angle in radians along the shorter arc.
         *
         * Plain [lerp] between `3.10` and `-3.10` rad takes the long way round: the sprite
         * spins almost a full turn to reach a pose two hundredths of a radian away. Reducing
         * the difference into `(-pi, pi]` first takes the arc a viewer expects.
         *
         * At `t == 1` the result is `from + delta`, which is `to` reduced modulo a full turn —
         * the same *heading*, and the same drawn sprite, even where it is not the same float.
         */
        public fun lerpAngle(from: Float, to: Float, t: Float): Float {
            var delta = (to - from) % TWO_PI
            if (delta > PI) delta -= TWO_PI
            if (delta < -PI) delta += TWO_PI
            return from + delta * t
        }

        private const val PI: Float = Math.PI.toFloat()
        private const val TWO_PI: Float = 2f * PI
    }
}
