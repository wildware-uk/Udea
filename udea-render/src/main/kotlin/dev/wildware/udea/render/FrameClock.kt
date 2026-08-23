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
internal class FrameTimer(private val clock: FrameClock) : FrameTime {

    private var previousNanos: Long = 0L
    private var started: Boolean = false

    override var frameSeconds: Float = 0f
        private set

    /**
     * Reads the clock once for this frame and publishes the result as [frameSeconds].
     *
     * Called by [RenderPipeline] at the top of the frame, before anything draws, so a
     * wall-timed renderer and the overlay are looking at the same number. Two readings inside
     * one frame would give the world and the overlay different ideas of how long it was, and
     * an animation that disagreed with the panel narrating it is the kind of drift nobody
     * looks for.
     *
     * @return seconds since the previous frame; `0f` on the first.
     */
    fun advance(): Float {
        frameSeconds = nextFrameSeconds()
        return frameSeconds
    }

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

/**
 * The wall delta of the frame being drawn, for the renderers that are entitled to one.
 *
 * ## Why this exists rather than a third parameter on `RenderSystem.render`
 *
 * [RenderSystem.render] takes a target and an `alpha` and nothing else, deliberately: a
 * renderer handed a delta in seconds could advance state over time, and state advanced outside
 * the tick is simulation that no snapshot captures and no replay reproduces (spec 3.3).
 *
 * Three renderers legitimately need one anyway, because what they advance is *not* simulation
 * and never enters a snapshot: a sprite animation's playhead, a particle effect's emitters,
 * and a UI stage's actions. Those three take a [FrameTime] as a **constructor parameter**, so
 * the dependency is declared at the registration site and visible in the type, rather than
 * available to every renderer by default. A renderer that does not ask for one cannot read
 * one.
 *
 * The value is republished once per frame by [RenderPipeline] before any system draws, so
 * every reader of it sees the same number for the same frame.
 */
public interface FrameTime {

    /**
     * Wall seconds since the previous frame, clamped to [RenderPipeline.MAX_FRAME_SECONDS].
     *
     * `0f` before the first frame has been drawn, and on the first frame itself: there is no
     * previous frame to measure against, and "nanoTime since the JVM started" would make every
     * animation begin mid-flight.
     */
    public val frameSeconds: Float
}
