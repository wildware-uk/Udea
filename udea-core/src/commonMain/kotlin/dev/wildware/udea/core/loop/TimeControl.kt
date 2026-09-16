package dev.wildware.udea.core.loop

import dev.wildware.udea.core.Tick

/**
 * The MCP-facing view of time.
 *
 * A facade rather than handing an agent the [GameLoop] itself: an agent may pause, resume,
 * single-step, change the rate, snapshot, rewind and fast-forward, and must not be able to
 * reach the accumulator, the [Presentation] or the [Simulation] behind them. Spec 1 promises
 * an agent can "pause, single-step, rewind sixty seconds and fast-forward" with no debug code
 * in the game; this is that promise's whole surface.
 *
 * The old engine could not express any of it: `common/UdeaGameManager.kt:235` ran
 * `world.update(delta)` with the raw frame delta inside `GameScreen.render`, so there was no
 * addressable tick and no pause that was not a frame-rate artefact.
 *
 * Every method is a whole-tick operation and every parameter is a [Tick] count, never seconds
 * (spec 5, "Time"), which is what makes an agent's observation reproducible: after `step(7)`
 * the world is at a named tick, not at a named wall time.
 *
 * ## Time travel is optional
 *
 * [travel] is the snapshot ring behind [rewind], [fastForward] and [snapshot]. A
 * [TimeControl] built without one is complete and working — every time-travel call returns
 * [RewindFailure.NoSnapshotRing] instead of throwing — which is what lets a headless loop test
 * drive this class with no world at all.
 */
public class TimeControl(
    private val loop: GameLoop,
    /** The snapshot ring, or `null` for a loop that keeps no history. */
    private val travel: TimeTravel? = null,
) {

    /** Whether the simulation is currently frozen. */
    public val paused: Boolean get() = loop.paused

    /** Simulated seconds per wall second. */
    public val timeScale: Float get() = loop.timeScale

    /** Ticks run since the loop was created. */
    public val totalTicks: Long get() = loop.totalTicks

    /**
     * Freezes the simulation. Rendering continues, so the agent can still take a picture.
     *
     * Returns only once the tick in flight has finished, so the world an agent reads
     * immediately afterwards is a whole one. On a single-threaded host that costs nothing —
     * there is no tick in flight when a tool call is being served — and on a `GameHost.run()`
     * host it is the difference between a pause and a request for one.
     */
    public fun pause() {
        loop.pauseAtBoundary()
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

    // --- time travel -------------------------------------------------------------------------

    /**
     * Captures the world as it stands and stores it in the ring.
     *
     * Deliberately does **not** pause, unlike [rewind]: a capture is an observation, and an
     * agent asking what the world looks like has not asked for the game to stop. It reads the
     * world without draining anything, so it needs a tick boundary and not a stopped loop —
     * and the loop is single-threaded, so "on the simulation thread" *is* a tick boundary.
     * Call it from a [BarrierAction] or from a paused loop; calling it from inside a system
     * would capture a half-stepped world.
     *
     * @throws IllegalStateException if this control has no [TimeTravel]. Unlike a rewind,
     *   which can reasonably be asked for a tick the ring no longer holds, asking a
     *   history-less loop to record history is a wiring mistake and not a runtime condition.
     */
    public fun snapshot(): SnapshotInfo {
        val ring = checkNotNull(travel) {
            "this TimeControl has no snapshot ring; build it with a TimeTravel to record history"
        }
        return ring.captureNow()
    }

    /** Every snapshot the ring holds, oldest first. Empty when there is no ring. */
    public fun listSnapshots(): List<SnapshotInfo> = travel?.listSnapshots() ?: emptyList()

    /**
     * Rewinds exactly [ticks] ticks and leaves the loop paused.
     *
     * Restores the newest keyframe at or before the target and then runs bare steps to close
     * the gap, which is what makes the landing *exact* at any [ticks] rather than only at
     * keyframe-aligned ones. That is also why the sparse cadence is the thing that degrades
     * under budget pressure and the window is not: a wider keyframe spacing costs a few more
     * of these steps and nothing else (spec 7).
     *
     * Failures are returned, never thrown — every one of them is a reasonable answer to a
     * reasonable question. See [RewindFailure].
     *
     * The loop is paused **before** the restore, not after it, and the pause waits for the
     * tick in flight ([GameLoop.pauseAtBoundary]). `SnapshotTimeTravel` forces a `SimBarrier`
     * drain from here rather than waiting for the next `Simulation.step`, and the only thing
     * that makes a forced drain safe is that the loop is stopped at a tick boundary when it
     * happens — which on a free-running host means stopped, not merely asked to stop. Pausing on
     * the way out instead, as `stepTicks` alone would, left that guarantee resting on the
     * accident that a tool call arrives between frames.
     *
     * **A refused rewind leaves the loop as it found it.** The pause exists to make the drain
     * safe, so it is taken only around the attempt and handed back on every failure that
     * touched nothing: an agent that probes with `rewind(10000)`, or asks for a scene the ring
     * no longer covers, gets an answer and a still-running game rather than a silently halted
     * one. [RewindFailure.RestoreFailed] is the exception and stays paused on purpose — the
     * apply threw part way through, so the world is in an undefined state and resuming it
     * would run systems over half-restored components.
     */
    public fun rewind(ticks: Int): RewindResult {
        require(ticks >= 0) { "rewind distance must not be negative, was $ticks" }
        val ring = travel ?: return RewindResult.Failed(
            RewindFailure.NoSnapshotRing,
            "this TimeControl was built without a snapshot ring",
        )

        val target = ring.currentTick - ticks.toLong()
        if (target.value < 0) {
            return RewindResult.Failed(
                RewindFailure.TickOutOfRing,
                "rewinding $ticks ticks from ${ring.currentTick} would land before the " +
                    "simulation started",
            )
        }

        // Before anything can restore or step, and before the forced drain inside
        // `restoreNearestAtOrBefore`: see the note above. `pauseAtBoundary` and not
        // `paused = true`, because on a host whose simulation runs on its own thread the flag
        // alone leaves the tick already inside `world.update` running underneath the restore.
        val wasPaused = loop.paused
        loop.pauseAtBoundary()

        return when (val outcome = ring.restoreNearestAtOrBefore(target)) {
            is RestoreOutcome.Refused -> {
                // Nothing was applied, so nothing about the running game has changed and the
                // pause it was taken under is not part of the answer. A half-applied restore
                // is the one case where it is.
                if (outcome.failure != RewindFailure.RestoreFailed) loop.paused = wasPaused
                RewindResult.Failed(outcome.failure, describe(outcome, target))
            }
            is RestoreOutcome.Restored -> {
                val steps = target.ticksSince(outcome.restoredTick).toInt()
                loop.stepTicks(steps)
                RewindResult.Rewound(
                    tick = target,
                    restoredFromTick = outcome.restoredTick,
                    steppedForward = steps,
                    assetGraphChangedSince = ring.assetGraphChangedSince(outcome.restoredTick),
                )
            }
        }
    }

    /**
     * Runs [ticks] bare steps as fast as the machine will and leaves the loop paused.
     *
     * Deliberately **not** a raised [timeScale]. A high time scale still runs through
     * [GameLoop.frame], which renders once per frame and is capped by `maxCatchUp` — so it
     * would be bounded by the GPU and by the catch-up ceiling, and would render thousands of
     * frames nobody looks at. This bypasses [Presentation] entirely: no render call is issued
     * at all, which is what makes fast-forwarding a hundred simulated seconds take
     * milliseconds and what makes it work in a headless process with no GL context (spec 3.5).
     *
     * It is the same mechanism as [step] under the name the agent surface uses, and it is
     * written as a delegation rather than a second copy of it precisely so the two can never
     * drift into meaning different things.
     */
    public fun fastForward(ticks: Int): Unit = step(ticks)

    /** The tick the simulation is at, or `null` for a control with no [TimeTravel] behind it. */
    public val currentTick: Tick? get() = travel?.currentTick

    private fun describe(outcome: RestoreOutcome.Refused, target: Tick): String = when (outcome.failure) {
        RewindFailure.TickOutOfRing ->
            "the ring no longer holds a snapshot at or before $target"

        RewindFailure.SceneMismatch ->
            "the snapshot at or before $target belongs to scene ${outcome.snapshotScene}, " +
                "but ${outcome.activeScene} is active; load the scene first"

        RewindFailure.NoSnapshotRing ->
            "this TimeControl was built without a snapshot ring"

        RewindFailure.RestoreFailed ->
            "applying the snapshot at or before $target threw; the world is in an undefined " +
                "state and is not at $target. See the SimBarrier log line for the action and " +
                "the exception"
    }
}
