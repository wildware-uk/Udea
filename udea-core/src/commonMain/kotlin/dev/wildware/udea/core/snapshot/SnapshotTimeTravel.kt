package dev.wildware.udea.core.snapshot

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.AssetGraphHistory
import dev.wildware.udea.core.loop.RestoreOutcome
import dev.wildware.udea.core.loop.RewindFailure
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.loop.SnapshotInfo
import dev.wildware.udea.core.loop.TimeControl
import dev.wildware.udea.core.loop.TimeTravel
import dev.wildware.udea.core.loop.TimeTravelFactory
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule

/**
 * The snapshot ring, wired up as the time-travel half of [TimeControl].
 *
 * [TimeTravel] is declared in the loop package so that `TimeControl` can offer `pause` and
 * `step` without depending on a ring, a world or a component registry. This is the
 * implementation, and it is the only place the two halves meet.
 *
 * ## The restore still goes through the barrier
 *
 * Spec 3.3 gives exactly one way to mutate a world from outside a system, and a restore is the
 * largest mutation there is. So [restoreNearestAtOrBefore] submits a named [SnapshotService]
 * action to [SimBarrier] and then drains the barrier itself, rather than waiting for the next
 * `Simulation.step()`.
 *
 * The drain is forced because the alternative does not work: a drain that happened as part of
 * a step would apply the restore *and then simulate a tick*, so a rewind could never land on
 * the tick it was asked for — it would always overshoot by one, and `rewind(100)` would be off
 * by one for the life of the engine. Forcing it is safe for the reason the barrier exists:
 * [TimeControl] pauses the loop first, so the drain happens at a tick boundary with no system
 * running, which is the *only* guarantee `SimBarrier` ever made.
 *
 * ## Threading
 *
 * On the simulation thread. `SimBarrier.submit` is thread-safe so a tool call may *queue* work
 * from an HTTP thread, but draining, stepping and reading the world are not — the agent host
 * marshals a tool call onto the loop thread before it reaches here.
 */
public class SnapshotTimeTravel(
    private val service: SnapshotService,
    /** The one ring. Time travel, rollback and replication baselines all read these slots. */
    public val ring: SnapshotRing,
    private val world: World,
    private val ctx: GameContext,
    private val barrier: SimBarrier,
    /**
     * How often [captureIfDue] takes a keyframe, in ticks. `0` turns the loop cadence off.
     *
     * Defaulted from the context so that the knob has exactly one production reader and it is
     * this line. `EngineConfig`'s KDoc names it; if this default is ever replaced by a literal,
     * the field goes back to being a claim rather than a configuration.
     */
    private val captureIntervalTicks: Int = ctx.config.snapshotIntervalTicks,
    private val assetGraph: AssetGraphHistory = AssetGraphHistory.Unchanged,
) : TimeTravel {

    init {
        require(captureIntervalTicks >= 0) {
            "captureIntervalTicks must not be negative, was $captureIntervalTicks"
        }
    }

    /** Ticks [captureIfDue] has actually put a new snapshot into the ring on. */
    public var capturedTicks: Long = 0L
        private set

    override val currentTick: Tick get() = ctx.clock.tick

    /**
     * Captures the current tick, or reports the snapshot already held for it.
     *
     * The agent-facing capture, behind `TimeControl.snapshot()`. It allocates a [SnapshotInfo],
     * because that value is the whole reason a caller asks; the loop calls [captureIfDue],
     * which does not.
     */
    override fun captureNow(): SnapshotInfo {
        val tick = currentTick
        capture(tick)
        return checkNotNull(ring.infoOf(tick)) {
            "the ring dropped the snapshot it was just handed at $tick"
        }
    }

    /**
     * Captures when `tick % `[captureIntervalTicks]` == 0`, allocating nothing when it does.
     *
     * `WorldSimulation.step` calls this on every tick of every simulation that has a ring, so
     * every branch below sits on the path `SnapshotBudgets.LOOP_ALLOCATED_BYTES` gates at zero
     * bytes: the modulo is on a raw `Long`, the held-already guard is [SnapshotRing.holds]
     * rather than [SnapshotRing.infoOf], and nothing here builds a [SnapshotInfo].
     *
     * Keyed on the clock rather than on a counter of its own, for two reasons. A counter would
     * drift the moment a rewind moved the clock backwards, so the ticks captured after a
     * rewind would stop lining up with the ones captured before it and a re-run would place
     * its keyframes somewhere else. And [SnapshotRing]'s own keyframe retention test is
     * `tick % sparseInterval`, so a cadence expressed in the same terms means a captured tick
     * either is a keyframe forever or never was one.
     */
    override fun captureIfDue(): Boolean {
        if (captureIntervalTicks == 0) return false
        val tick = currentTick
        if (tick.value % captureIntervalTicks != 0L) return false
        if (!capture(tick)) return false
        capturedTicks++
        return true
    }

    /**
     * Puts [tick] in the ring unless it is already there. Allocation-free.
     *
     * Idempotent per tick on purpose, and capturing twice at one tick would produce two
     * identical slots anyway: the world cannot change between them, because nothing steps in
     * between.
     *
     * The caller it exists for is `TimeControl.snapshot()`, and only that one. A rewind's
     * step-forward does **not** need it: `SnapshotService.applyNow` sets the clock to the
     * restored tick, and the first `Simulation.step` after it drains, updates, advances the
     * clock and only *then* calls [captureIfDue] — so that step offers `restoredTick + 1`, and
     * the restored tick is never re-offered by the loop at all. What does re-offer a held tick
     * is an agent calling `snapshot()` at a tick the cadence already captured, which it has no
     * way to predict; without this guard that call would hit [SnapshotRing.commit]'s refusal to
     * accept a non-advancing commit and throw for a reason the caller could not act on.
     *
     * @return true if a new slot entered the ring.
     */
    private fun capture(tick: Tick): Boolean {
        if (ring.holds(tick)) return false
        val slot = ring.acquire()
        try {
            service.captureInto(slot)
        } catch (failure: Throwable) {
            // The slot never entered the ring, so returning it leaves the history exactly as
            // it was. Rethrown: a capture that cannot complete is a defect, not a condition.
            ring.release(slot)
            throw failure
        }
        ring.commit(slot)
        return true
    }

    override fun listSnapshots(): List<SnapshotInfo> = ring.listSnapshots()

    override fun assetGraphChangedSince(since: Tick): Boolean = assetGraph.changedSince(since)

    override fun restoreNearestAtOrBefore(target: Tick): RestoreOutcome {
        val slot = ring.nearestAtOrBefore(target) ?: return RestoreOutcome.Refused(
            failure = RewindFailure.TickOutOfRing,
            snapshotScene = null,
            activeScene = ctx.scenes.activeSceneId,
        )

        // Checked here rather than left to SnapshotService.applyNow, which throws: the barrier
        // logs and continues past a failing action by design, so an exception raised inside the
        // drain would leave the caller believing the rewind landed. Refusing before anything is
        // submitted is also what makes "the world is untouched" true rather than nearly true.
        val active = ctx.scenes.activeSceneId
        if (slot.scene != active) {
            return RestoreOutcome.Refused(
                failure = RewindFailure.SceneMismatch,
                snapshotScene = slot.scene,
                activeScene = active,
            )
        }

        val restoredTick = slot.tick
        val action = service.restore(slot, barrier)
        barrier.drain(world, ctx)
        if (action.failure != null) {
            // `SimBarrier.drain` catches, logs and carries on past a throwing action by design,
            // so without this the world would be half-restored — the clock possibly never
            // moved — and `TimeControl.rewind` would still report `Rewound(tick = target)`,
            // handing an agent a tick it is not at. Asked of the action rather than of
            // `barrier.failedActions`, which also counts whatever unrelated tool call happened
            // to be queued behind this restore.
            //
            // The ring is left exactly as it is: the slots after `restoredTick` record a future
            // that may or may not have been unwound, and guessing which would be worse than
            // saying the restore failed.
            return RestoreOutcome.Refused(
                failure = RewindFailure.RestoreFailed,
                snapshotScene = slot.scene,
                activeScene = ctx.scenes.activeSceneId,
            )
        }

        // Everything after the restored tick is a future that has just been unwound.
        ring.dropAfter(restoredTick)
        return RestoreOutcome.Restored(restoredTick)
    }

    override fun toString(): String = "SnapshotTimeTravel(tick=$currentTick, ring=$ring)"
}

/**
 * The [TimeTravelFactory] a host hands `UdeaGameDef` to give a game history.
 *
 * A function rather than a constructor call at the wiring site, because the ring, the capture
 * service and the time-travel facade are three objects that only ever appear together and two
 * of them need the `World` — which does not exist until the definition has built one. Supplying
 * this is the whole of the "does this game record?" decision; see the "when capture is active"
 * note on [dev.wildware.udea.core.loop.SnapshotKind].
 *
 * [registry] is generated per game, and that is exactly why the kernel cannot wire this itself:
 * `CoreModule` has no [ComponentRegistry] to invent. A host that has one calls this; one that
 * does not gets a simulation with no ring and pays nothing for it.
 *
 * The [NetIdIndex] and the [SimBarrier] are taken off the context rather than from the caller,
 * because `CoreModule` owns both and a snapshot built against a *different* id index would
 * capture a roster the world does not have. `UdeaGameDef` builds its `CoreModule` itself, so
 * asking a caller for them would be asking it to reach into a definition for values it cannot
 * get wrong.
 */
public fun snapshotTimeTravel(
    registry: ComponentRegistry,
    ringConfig: RingConfig = RingConfig(),
    assetGraph: AssetGraphHistory = AssetGraphHistory.Unchanged,
): TimeTravelFactory = TimeTravelFactory { ctx, world ->
    val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]
    SnapshotTimeTravel(
        service = SnapshotService(registry, world, ctx, netIds),
        ring = SnapshotRing(registry, ringConfig, ctx.log),
        world = world,
        ctx = ctx,
        barrier = ctx.barrier,
        assetGraph = assetGraph,
    )
}
