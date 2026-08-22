package dev.wildware.udea.render

/**
 * Wall time, for presentation only.
 *
 * Seconds exist in exactly two places in this engine -- this module and audio (spec 5,
 * "Time") -- and this is the seam where they enter. Everything simulated is denominated in
 * `Tick` and reads `SimClock`; an overlay animation, a fade or a spinner is not simulated and
 * legitimately wants "how long since the last frame".
 *
 * It is an interface for one reason: a test must be able to hand the pipeline a controlled
 * sequence of instants. A `System.nanoTime()` call buried in [RenderPipeline] would make
 * "the overlay was given the right dtSeconds" an untestable claim, and this whole module
 * exists to make presentation testable without a GL context.
 */
public fun interface FrameClock {

    /**
     * A monotonically non-decreasing reading in nanoseconds. Only *differences* are ever
     * used, so the epoch is irrelevant.
     */
    public fun nanoTime(): Long

    public companion object {

        /** The real clock: `System.nanoTime`, monotonic and unaffected by wall-clock jumps. */
        public val Wall: FrameClock = FrameClock { System.nanoTime() }
    }
}

/**
 * Turns a [FrameClock] into the per-frame delta an [OverlaySystem] is handed.
 *
 * Two guards, both of which have bitten this engine's ancestor:
 *
 * - the **first** frame has no predecessor, so it yields `0f` rather than "nanoTime since
 *   the JVM started", which would make every overlay animation begin mid-flight;
 * - a delta is clamped to [RenderPipeline.MAX_FRAME_SECONDS], because a breakpoint, a GC
 *   pause or a laptop lid closing produces a 40-second frame, and an overlay that advanced
 *   an animation by 40 seconds would snap rather than animate. `GameLoop` clamps the
 *   simulation side for the same reason and with the same figure.
 *
 * A non-monotonic clock (only a broken fake, in practice) yields `0f` rather than a negative
 * delta: time running backwards in an animation is worse than a dropped frame.
 */
internal class FrameTimer(private val clock: FrameClock) {

    private var previousNanos: Long = 0L
    private var started: Boolean = false

    /** Seconds since the previous call; `0f` on the first. */
    fun nextFrameSeconds(): Float {
        val now = clock.nanoTime()
        if (!started) {
            started = true
            previousNanos = now
            return 0f
        }
        val elapsed = now - previousNanos
        previousNanos = now
        if (elapsed <= 0L) return 0f
        val seconds = elapsed.toFloat() / NANOS_PER_SECOND
        return if (seconds > RenderPipeline.MAX_FRAME_SECONDS) {
            RenderPipeline.MAX_FRAME_SECONDS
        } else {
            seconds
        }
    }

    private companion object {
        const val NANOS_PER_SECOND: Float = 1_000_000_000f
    }
}
