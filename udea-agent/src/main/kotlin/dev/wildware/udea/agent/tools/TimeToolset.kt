package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.dispatch.AgentContext
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.annotations.Arg
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.loop.GameLoop
import dev.wildware.udea.core.loop.RewindFailure
import dev.wildware.udea.core.loop.RewindResult
import dev.wildware.udea.core.loop.SnapshotInfo
import dev.wildware.udea.core.loop.TimeControl

/**
 * Time travel as an agent sees it: pause, step, scale, snapshot, rewind, fast-forward.
 *
 * This is what makes the surface more than a remote console. Step 200 ticks, screenshot, rewind
 * 100, screenshot again, diff - and every one of those is a tick count, never a wall time and
 * never a frame count.
 *
 * ## Ticks, not frames, and the tools say so
 *
 * The defect this must not reproduce is recorded at `DebugBridge.kt:40`: a harness that waited
 * on *render* frames made `step(n)` approximate, which silently made every determinism
 * measurement compare runs of different lengths. So [TimeToolset] returns the tick before and
 * the tick after every step, and every description tells the caller to wait on `tick` - because
 * a paused game still advances `frame`, and an agent that waits on the wrong one waits forever
 * or not at all.
 *
 * `simFrame` in the Tier-0 digest is the same number. Both names are published because
 * `game-bridge-mcp` reads `simFrame` and this engine says `tick` everywhere else.
 *
 * ## Where each tool runs, and the one that cannot run where it was called
 *
 * `pause`, `resume`, `set_time_scale`, `snapshot` and `list_snapshots` do their work inside the
 * `SimBarrier` drain `AgentRuntime` posted the command to, like [WorldToolset] - a tick
 * boundary, which is the only state in which a capture is a coherent world rather than a
 * half-stepped one.
 *
 * `step`, `fast_forward` and `rewind` cannot. All three **run the simulation**, and
 * `Simulation.step` drains the barrier, and `SimBarrier.drain` refuses to re-enter - a nested
 * drain swaps the batch the outer one is walking and destroys every action queued since it
 * started. `TimeControl.rewind` states the same requirement from the other side: it forces a
 * drain of its own, and the only thing that makes that safe is the loop being stopped between
 * frames. So those three go through [dev.wildware.udea.agent.dispatch.AgentContext.answerLater],
 * which runs them after the tick, outside any drain, and completes the command with what they
 * actually did. The confirmation still means "it happened", and the digest that carries it is
 * the one published after the work ran.
 *
 * A paused world still drains: `AgentRuntime.afterFrame(0)` drains the barrier and then runs
 * the deferred work when the loop took no steps, which is what makes `time.resume` - and a
 * `step` of a paused game - reachable at all.
 */
public class TimeToolset(
    private val control: TimeControl,
    private val clock: SimClock,
    private val bridge: AgentBridge,
) {

    @AgentTool(
        name = "time.pause",
        description = "Freeze the simulation. Rendering continues, so screenshots still " +
            "work and the frame counter still moves - wait on tick, never on frame, to " +
            "tell a paused game from a stalled one.",
    )
    public fun pause(): AgentResult {
        control.pause()
        return state()
    }

    @AgentTool(
        name = "time.resume",
        description = "Unfreeze the simulation and let it step at its normal rate again. " +
            "Reach for it after a paused investigation, or after time.step or time.rewind, " +
            "both of which leave the loop paused.",
    )
    public fun resume(): AgentResult {
        control.resume()
        return state()
    }

    /**
     * Steps exactly [ticks] ticks and reports the tick either side.
     *
     * Reporting both is what lets a caller assert exactness without polling: `after - before`
     * is the number that was actually run, and a caller that only saw `after` would have to
     * have remembered `before` from a separate read that may have raced a running loop.
     */
    @AgentTool(
        name = "time.step",
        description = "Advance the simulation by exactly this many ticks with no " +
            "rendering, and report the tick before and after. This is the tool to reach " +
            "for whenever an assertion has to be about a named tick rather than a moment.",
    )
    public fun step(
        context: AgentContext,
        @Arg(
            description = "Ticks to advance. One tick is one 1/60s simulation step.",
            required = false,
            default = "1",
        )
        ticks: Int,
    ): AgentResult? {
        if (ticks < 0) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "time.step got ticks=$ticks; stepping backwards is time.rewind, which restores " +
                    "a snapshot rather than running the simulation in reverse",
            )
        }
        context.answerLater { stepped(ticks) { control.step(ticks) } }
        return null
    }

    @AgentTool(
        name = "time.set_time_scale",
        description = "Set how many fixed steps run per host frame. It never changes dt " +
            "- the 1/60s step is invariant - so slowing time down does not change any " +
            "physics result, only how much of it you see per second.",
    )
    public fun setTimeScale(
        @Arg(description = "Simulated seconds per wall second, from 0 to 8.")
        scale: Float,
    ): AgentResult {
        if (scale < 0f || scale > GameLoop.MAX_TIME_SCALE) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "time.set_time_scale got scale=$scale; it must be between 0 and " +
                    "${GameLoop.MAX_TIME_SCALE}. An absurd scale wraps the loop's accumulator " +
                    "negative and wedges the simulation silently, so it is refused rather than " +
                    "clamped",
            )
        }
        control.timeScale(scale)
        bridge.event("agent_time:set_time_scale:$scale", clock.tick.value)
        return state()
    }

    @AgentTool(
        name = "time.snapshot",
        description = "Capture the world as it stands into the snapshot ring and return " +
            "its tick and size. It does not pause: a capture is an observation, so use " +
            "it to mark a state you intend to come back to.",
    )
    public fun snapshot(): AgentResult {
        val info = try {
            control.snapshot()
        } catch (missing: IllegalStateException) {
            return AgentResult.failed(NO_SNAPSHOT_RING, missing.message ?: "no snapshot ring")
        }
        bridge.event("agent_time:snapshot:${info.tick.value}", clock.tick.value)
        return AgentResult.ok { renderSnapshot(info) }
    }

    /**
     * The snapshot ring, one page at a time. See [ResultPage] for why this is paged at all.
     *
     * This is the tool that made the hole visible: a full ring is hundreds of entries, the
     * whole list is several kilobytes, and `commandResults` drops a result that does not fit
     * rather than shortening it - so before paging, `time.list_snapshots` on a busy game could
     * not answer at all. `totalBytes` is over the **whole** ring and not the page, because
     * "how much history am I holding" is the question the number is for.
     */
    @AgentTool(
        name = "time.list_snapshots",
        description = "List the snapshots the ring is holding with each one's tick, kind and " +
            "byte size, one page at a time. Read it before time.rewind to see how far the " +
            "history actually reaches; follow nextOffset for the rest.",
    )
    public fun listSnapshots(
        @Arg(description = "How many snapshots to skip, from the oldest.", required = false, default = "0")
        offset: Int,
        @Arg(
            description = "Most snapshots to return. A page also stops early when it runs out " +
                "of the bytes a command result is guaranteed in the state document.",
            required = false,
            default = "16",
        )
        limit: Int,
    ): AgentResult {
        val held = control.listSnapshots()
        val totalBytes = held.sumOf(SnapshotInfo::sizeBytes)
        return ResultPage.render(
            name = "snapshots",
            offset = offset,
            limit = limit,
            total = held.size,
            prelude = {
                // `count` was the field before paging and is kept beside `total`, which is the
                // page vocabulary: a caller written against the old shape still reads the same
                // number rather than silently reading `returned`, which is a different one.
                put("count", held.size)
                put("totalBytes", totalBytes)
            },
        ) { json, index, _ -> json.obj { renderSnapshot(held[index]) } }
    }

    @AgentTool(
        name = "time.rewind",
        description = "Restore the world to a tick in the past and leave it paused. The " +
            "landing is exact at any distance. Use it to re-examine the moment before a " +
            "bug rather than reproducing it again.",
    )
    public fun rewind(
        context: AgentContext,
        @Arg(description = "How many ticks back from now to land on.")
        ticks: Int,
    ): AgentResult? {
        if (ticks < 0) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "time.rewind got ticks=$ticks; a rewind distance is how far back to go",
            )
        }
        context.answerLater { travel(ticks) }
        return null
    }

    /**
     * Fast-forward is [TimeControl.fastForward], which is [TimeControl.step] under the name the
     * agent surface uses - deliberately not a raised time scale, which would still render a
     * frame per step and be bounded by the GPU.
     */
    @AgentTool(
        name = "time.fast_forward",
        description = "Run the simulation forwards as fast as the machine will, with no " +
            "rendering at all, and leave it paused. Use it to reach a late-game state " +
            "in milliseconds instead of raising the time scale, which still renders.",
    )
    public fun fastForward(
        context: AgentContext,
        @Arg(description = "How many ticks to run.")
        ticks: Int,
    ): AgentResult? {
        if (ticks < 0) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "time.fast_forward got ticks=$ticks; it runs the simulation forwards",
            )
        }
        context.answerLater {
            stepped(ticks) {
                control.fastForward(ticks)
                bridge.event("agent_time:fast_forward:$ticks", clock.tick.value)
            }
        }
        return null
    }

    /**
     * Runs [work] and reports the tick either side of it.
     *
     * Both, because reporting both is what lets a caller assert exactness without polling:
     * `tickAfter - tickBefore` is what actually ran, and a caller given only `tickAfter` would
     * have to have remembered `tickBefore` from a separate read that may have raced the loop.
     */
    private inline fun stepped(ticks: Int, work: () -> Unit): AgentResult {
        val before = clock.tick.value
        work()
        val after = clock.tick.value
        return AgentResult.ok {
            put("tickBefore", before)
            put("tickAfter", after)
            put("ticksStepped", after - before)
            put("ticksRequested", ticks)
            put("paused", control.paused)
        }
    }

    private fun travel(ticks: Int): AgentResult =
        when (val outcome = control.rewind(ticks)) {
            is RewindResult.Rewound -> {
                bridge.event("agent_time:rewind:${outcome.tick.value}", clock.tick.value)
                AgentResult.ok {
                    put("tick", outcome.tick.value)
                    put("restoredFromTick", outcome.restoredFromTick.value)
                    put("steppedForward", outcome.steppedForward)
                    // Not an error, and the description says so: an agent that rewound past a
                    // hot-reload is looking at a world whose blueprints have since changed, and
                    // a rewind that did not say so would have it comparing two different games.
                    put("assetGraphChangedSince", outcome.assetGraphChangedSince)
                    put("paused", true)
                }
            }

            is RewindResult.Failed -> AgentResult.failed(
                AgentErrorKind(outcome.failure.code),
                outcome.detail,
            )
        }

    private fun state(): AgentResult = AgentResult.ok {
        put("tick", clock.tick.value)
        put("paused", control.paused)
        put("timeScale", control.timeScale)
        put("totalTicks", control.totalTicks)
    }

    private fun Json.renderSnapshot(info: SnapshotInfo) {
        put("tick", info.tick.value)
        put("kind", info.kind.name)
        put("sizeBytes", info.sizeBytes)
    }

    override fun toString(): String = "TimeToolset(tick=${clock.tick.value}, paused=${control.paused})"

    public companion object {

        /**
         * This simulation keeps no history, so there is nothing to snapshot or rewind through.
         *
         * The same spelling [RewindFailure.NoSnapshotRing] uses, so a refusal reads the same
         * whether it came from `snapshot` - which throws, because asking a history-less loop to
         * record history is a wiring mistake - or from `rewind`, which returns it.
         */
        public val NO_SNAPSHOT_RING: AgentErrorKind =
            AgentErrorKind(RewindFailure.NoSnapshotRing.code)
    }
}
