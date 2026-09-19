package dev.wildware.udea.render.model

import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.ClipPlayback
import dev.wildware.udea.core.spatial.Loop

/**
 * Where an entity's animation clips are this frame, as plain numbers: which clip, how far into it,
 * which clip it is fading from and how far into that, and how much of each to show (issue #242).
 *
 * The renderer's reading of an `Animator`, and the only place a clip's time becomes seconds. It is
 * a pure function of the `Animator`, the tick and the interpolation alpha - [set] reads nothing
 * else and remembers nothing from one call to the next - so two runs of the same recorded session
 * draw the same pose at the same tick and alpha, and a rewind draws the pose of the tick it lands
 * on. `ModelStage` writes these numbers into Kool's skinned model, once per entity per frame.
 *
 * One instance is reused for every entity every frame: setting it allocates nothing.
 *
 * ## Between ticks
 *
 * The simulation's `Animator.clipTime` is whole ticks. The picture moves on between them by the
 * interpolation alpha, scaled by the clip's speed: at speed 1 the clip time is exactly
 * `clipTime + alpha` ticks, and at any speed its whole part at alpha 0 is exactly the simulation's
 * `clipTime`, so what the picture shows never disagrees with what gameplay code is told. A
 * crossfade's weight moves on between ticks the same way, from `Animator.blendWeight`.
 */
internal class ClipPose {

    /** The clip playing now, as `AnimationClip.index`, or [ClipPlayback.NO_CLIP] for the bind pose. */
    var current: Int = ClipPlayback.NO_CLIP
        private set

    /** How far into [current] the picture is, in clip ticks: `0` up to the clip's length. */
    var currentTicks: Double = 0.0
        private set

    /** The clip [current] is fading in over, or [ClipPlayback.NO_CLIP] when nothing is fading out. */
    var previous: Int = ClipPlayback.NO_CLIP
        private set

    /** How far into [previous] the picture is, in clip ticks. */
    var previousTicks: Double = 0.0
        private set

    /** How much of [current] to show, `0` to `1`; [previous] gets the rest. */
    var weight: Float = 1f
        private set

    /** [currentTicks] in seconds, the unit Kool samples a clip in. */
    val currentSeconds: Float get() = seconds(currentTicks)

    /** [previousTicks] in seconds. */
    val previousSeconds: Float get() = seconds(previousTicks)

    /**
     * The pose of [animator] at [now], [alpha] of the way to the next tick. A `null` animator, or
     * one with nothing playing, is the bind pose.
     */
    fun set(animator: Animator?, now: Tick, alpha: Float): ClipPose {
        if (animator == null || animator.current.isEmpty()) {
            current = ClipPlayback.NO_CLIP
            currentTicks = 0.0
            clearPrevious()
            weight = 1f
            return this
        }
        current = animator.current.clip
        currentTicks = clipTicks(animator.current, now, alpha)
        weight = fadeWeight(animator, now, alpha)
        if (weight < 1f) {
            previous = animator.previous.clip
            previousTicks = clipTicks(animator.previous, now, alpha)
        } else {
            clearPrevious()
        }
        return this
    }

    private fun clearPrevious() {
        previous = ClipPlayback.NO_CLIP
        previousTicks = 0.0
    }

    private companion object {

        /**
         * [playback]'s clip time at [now] plus [alpha] of a tick, in clip ticks: `(elapsed + alpha)
         * * speed`, wrapped or held as its [Loop] says. The same arithmetic as `ClipPlayback`'s
         * `clipTime` without its rounding down, taken in `Double` for the same reason.
         */
        fun clipTicks(playback: ClipPlayback, now: Tick, alpha: Float): Double {
            val length = playback.length
            if (length == 0L) return 0.0
            val elapsed = now.ticksSince(playback.start)
            if (elapsed < 0L) return 0.0
            val scaled = (elapsed.toDouble() + alpha.toDouble()) * playback.speed.toDouble()
            return when (playback.loop) {
                Loop.Repeat -> scaled % length.toDouble()
                Loop.Once -> minOf(scaled, length.toDouble())
            }
        }

        /**
         * `Animator.blendWeight` moved on by [alpha] of a tick: the same division, in `Float`, of
         * the elapsed ticks plus [alpha], so at alpha 0 it is the simulation's weight to the bit.
         */
        fun fadeWeight(animator: Animator, now: Tick, alpha: Float): Float {
            val length = animator.fadeLength
            if (length <= 0L || animator.previous.isEmpty()) return 1f
            val elapsed = now.ticksSince(animator.fadeStart)
            if (elapsed < 0L) return 0f
            return ((elapsed.toFloat() + alpha) / length.toFloat()).coerceIn(0f, 1f)
        }

        /** Clip ticks to seconds, at the rate the asset build measured every clip's length in. */
        fun seconds(clipTicks: Double): Float = (clipTicks / SimClock.DEFAULT_TICK_RATE).toFloat()
    }
}
