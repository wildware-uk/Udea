package dev.wildware.udea.core.loop

/**
 * The MCP-facing view of time.
 *
 * A facade rather than handing an agent the [GameLoop] itself: an agent may pause, resume,
 * single-step and change the rate, and must not be able to reach the accumulator, the
 * [Presentation] or the [Simulation] behind them. The snapshot epic adds `rewind` and
 * `fastForward` here, both implemented over [GameLoop.stepTicks].
 *
 * Every method is a whole-tick operation, which is what makes an agent's observation
 * reproducible: after `step(7)` the world is at a named tick, not at a named wall time.
 */
public class TimeControl(private val loop: GameLoop) {

    /** Whether the simulation is currently frozen. */
    public val paused: Boolean get() = loop.paused

    /** Simulated seconds per wall second. */
    public val timeScale: Float get() = loop.timeScale

    /** Ticks run since the loop was created. */
    public val totalTicks: Long get() = loop.totalTicks

    /** Freezes the simulation. Rendering continues, so the agent can still take a picture. */
    public fun pause() {
        loop.paused = true
    }

    /** Resumes normal stepping. */
    public fun resume() {
        loop.paused = false
    }

    /**
     * Pauses and advances exactly [n] ticks with no render.
     *
     * @throws IllegalArgumentException if [n] is negative. Stepping backwards is `rewind`,
     *   which is a snapshot operation and not the loop's to fake.
     */
    public fun step(n: Int = 1) {
        loop.stepTicks(n)
    }

    /**
     * Sets [GameLoop.timeScale].
     *
     * @throws IllegalArgumentException unless [x] is in `0..`[GameLoop.MAX_TIME_SCALE]. The
     *   ceiling is not tidiness: this is an LLM-facing setter, and an absurd scale wraps the
     *   loop's accumulator negative and wedges the simulation permanently and silently.
     */
    public fun timeScale(x: Float) {
        loop.timeScale = x
    }
}
