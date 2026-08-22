package dev.wildware.udea.core.loop

import dev.wildware.udea.core.EngineConfig
import kotlin.math.roundToLong

/**
 * The fixed-step loop: 60Hz simulation, render decoupled with an interpolation alpha
 * (spec 3.3).
 *
 * ## What it replaces
 *
 * `GameScreen.render` called `world.update(delta)` with the raw frame delta
 * (`common/UdeaGameManager.kt:222-236`), so a tick was however long the GPU took. Everything
 * downstream inherited that: snapshot cadence, rollback, replay and any two machines
 * agreeing on the same simulated second. Here the simulation only ever advances in whole
 * ticks, and the leftover fraction becomes [alpha] — a *presentation* value that never
 * reaches a system.
 *
 * ## Wall time is injected
 *
 * [frame] takes the delta; nothing here reads `Gdx.graphics.deltaTime`. That is what lets
 * the whole class be tested with no LibGDX `Application`, and what lets the agent's harness
 * feed synthetic time and fast-forward.
 *
 * ## The accumulator is an integer
 *
 * The textbook accumulator is `Float` seconds, and it is subtly wrong at 60Hz: `1f / 60f` is
 * `0.016666668`, *larger* than a sixtieth of a second, so ten seconds of wall time yields 599
 * ticks and a stubborn residue. This one counts in **sub-tick units**, [UNITS_PER_TICK] to
 * the tick:
 *
 * ```
 * accumulator += round(seconds * 1e6) * tickRate   // microseconds x ticks-per-second
 * ```
 *
 * so one tick is exactly `1e6` units at any tick rate, ten wall seconds is exactly 600 ticks
 * at 60Hz, and a `timeScale` of 0.5 yields exactly half as many ticks rather than
 * half-plus-drift. Microseconds rather than nanoseconds because a `Float` delta only carries
 * about seven digits: a millisecond expressed as a `Float` is exact to the microsecond and
 * off by nanoseconds, so rounding finer than a microsecond would preserve the float's error
 * instead of discarding it. It cannot overflow: a clamped frame contributes at most
 * `0.25 * 1e6 * tickRate` units, fifteen orders of magnitude below `Long.MAX_VALUE`.
 *
 * ## Spiral of death
 *
 * Two independent guards, because they fail in different ways:
 *
 * - **[MAX_WALL_DELTA]** clamps the *input*, before scaling. A breakpoint, a GC pause or a
 *   laptop lid closing hands the loop a 40-second delta; without the clamp the loop would
 *   try to simulate 40 seconds inside one frame.
 * - **[maxCatchUp]** clamps the *output*: at most that many ticks run in one [frame], and on
 *   hitting the cap the accumulator is **truncated to zero** rather than carried. Carrying
 *   the residue is what makes a stall spiral — each frame then owes more time than it can
 *   repay, and the debt grows. Truncating drops simulated time on the floor, which is the
 *   correct trade: a stalled client falls behind the server and resyncs from a snapshot,
 *   whereas a spiralling client never renders again. [truncatedFrames] counts it so the
 *   choice is visible rather than silent.
 */
public class GameLoop(
    private val sim: Simulation,
    private val view: Presentation? = null,
    /** Ceiling on ticks simulated in one [frame]. See [EngineConfig.maxCatchUpTicks]. */
    public val maxCatchUp: Int = DEFAULT_MAX_CATCH_UP,
) {

    init {
        require(maxCatchUp > 0) { "maxCatchUp must be positive, was $maxCatchUp" }
    }

    /**
     * Simulated seconds per wall second. `0f` freezes time exactly like [paused]; `2f` runs
     * the simulation at double speed without changing the tick rate, so a replay stays valid.
     */
    public var timeScale: Float = 1f
        set(value) {
            require(value >= 0f && value.isFinite()) {
                "timeScale must be finite and not negative, was $value"
            }
            field = value
        }

    /** While true, [frame] renders but never steps. [stepTicks] still advances the sim. */
    public var paused: Boolean = false

    /** Unsimulated time carried between frames, in [UNITS_PER_TICK]ths of a tick. */
    private var accumulator: Long = 0L

    /**
     * How far the next render sits between the last simulated tick and the next one.
     *
     * Always in `[0, 1)`: the accumulator is reduced below one tick before this is read.
     */
    public val alpha: Float
        get() = accumulator.toFloat() / UNITS_PER_TICK.toFloat()

    /** Ticks run by the most recent [frame]. Zero while paused. */
    public var lastFrameTicks: Int = 0
        private set

    /** Ticks run since construction, by [frame] and [stepTicks] alike. */
    public var totalTicks: Long = 0L
        private set

    /** Frames that hit [maxCatchUp] and discarded their residue. A health signal, not state. */
    public var truncatedFrames: Long = 0L
        private set

    /**
     * Advances the simulation by whole ticks worth of [wallDelta], then renders once.
     *
     * @param wallDelta real seconds since the previous frame, **injected** by the caller.
     *   Clamped to [MAX_WALL_DELTA] before [timeScale] is applied; a negative delta
     *   contributes nothing rather than rewinding.
     */
    public fun frame(wallDelta: Float) {
        var ticks = 0

        if (!paused && timeScale > 0f && wallDelta > 0f && wallDelta.isFinite()) {
            accumulator += subTickUnits(wallDelta.coerceAtMost(MAX_WALL_DELTA) * timeScale)

            while (accumulator >= UNITS_PER_TICK && ticks < maxCatchUp) {
                sim.step()
                accumulator -= UNITS_PER_TICK
                ticks++
            }

            if (accumulator >= UNITS_PER_TICK) {
                // Hit the cap with time still owed. Drop it: see "spiral of death" above.
                accumulator = 0L
                truncatedFrames++
            }
        }

        lastFrameTicks = ticks
        totalTicks += ticks

        view?.render(alpha)
    }

    /**
     * Pauses and advances exactly [n] ticks, rendering nothing.
     *
     * The primitive behind the agent's `step(n)` and, later, behind the snapshot epic's
     * `rewind`/`fastForward`: an agent that wants to inspect the world after exactly seven
     * ticks must not have a frame's worth of wall time smeared into the answer. The
     * accumulator is left alone — it holds real elapsed time that has still not been
     * simulated, and discarding it here would make `step(1)` quietly lose a frame's worth of
     * pending time.
     */
    public fun stepTicks(n: Int) {
        require(n >= 0) { "step count must not be negative, was $n" }
        paused = true
        repeat(n) { sim.step() }
        totalTicks += n
    }

    /** Wall time carried into the next frame, in seconds. Diagnostics only; not sim state. */
    public val pendingSeconds: Double
        get() = accumulator.toDouble() / (UNITS_PER_TICK.toDouble() * sim.tickRate)

    /** True when no wall time is owed at all — what a truncating frame leaves behind. */
    public val isAccumulatorEmpty: Boolean get() = accumulator == 0L

    /** Wall seconds to sub-tick units, exactly: microseconds times ticks per second. */
    private fun subTickUnits(seconds: Float): Long =
        (seconds.toDouble() * MICROS_PER_SECOND).roundToLong() * sim.tickRate

    public companion object {
        /**
         * Sub-tick units in one tick. Chosen as `1e6` so that a microsecond-resolution wall
         * delta converts exactly at every plausible tick rate.
         */
        public const val UNITS_PER_TICK: Long = 1_000_000L

        private const val MICROS_PER_SECOND: Double = 1_000_000.0

        /**
         * Longest wall delta the loop will believe, in seconds (spec 3.3's design figure).
         * A larger one is a stall, not elapsed gameplay.
         */
        public const val MAX_WALL_DELTA: Float = 0.25f

        /** Matches [EngineConfig.maxCatchUpTicks]'s default. */
        public const val DEFAULT_MAX_CATCH_UP: Int = 5

        /**
         * A loop configured from the simulation's own [EngineConfig].
         *
         * Preferred over the constructor for production wiring: it takes [maxCatchUp] from
         * `ctx.config` instead of the default, so the knob lives in exactly one place.
         */
        public fun forWorld(sim: WorldSimulation, view: Presentation? = null): GameLoop =
            GameLoop(sim, view, sim.ctx.config.maxCatchUpTicks)
    }
}
