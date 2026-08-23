package dev.wildware.udea.core.snapshot

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.loop.AssetGraphHistory
import dev.wildware.udea.core.loop.RestoreOutcome
import dev.wildware.udea.core.loop.RewindFailure
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.loop.SnapshotInfo
import dev.wildware.udea.core.loop.TimeControl
import dev.wildware.udea.core.loop.TimeTravel

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
    private val assetGraph: AssetGraphHistory = AssetGraphHistory.Unchanged,
) : TimeTravel {

    override val currentTick: Tick get() = ctx.clock.tick

    /**
     * Captures the current tick, or reports the snapshot already held for it.
     *
     * Idempotent per tick on purpose. An agent that pauses and then asks for a snapshot has no
     * way to know whether the loop already captured this tick, and the ring refuses a
     * non-advancing commit — so without this, `snapshot()` would throw for a reason the caller
     * could neither predict nor act on. Capturing twice at one tick would produce two identical
     * slots anyway: the world cannot change between them, because nothing steps in between.
     *
     * The idempotence guard is [SnapshotRing.holds] and not [SnapshotRing.infoOf]: `infoOf`
     * builds a [SnapshotInfo], and asking it whether a tick is held would put one object per
     * captured tick on the path spec 7 budgets at zero. The single `SnapshotInfo` this returns
     * is the value the caller asked for, not a probe.
     */
    override fun captureNow(): SnapshotInfo {
        val tick = currentTick
        if (!ring.holds(tick)) {
            val slot = ring.acquire()
            try {
                service.captureInto(slot)
            } catch (failure: Throwable) {
                // The slot never entered the ring, so returning it leaves the history exactly
                // as it was. Rethrown: a capture that cannot complete is a defect, not a
                // condition.
                ring.release(slot)
                throw failure
            }
            ring.commit(slot)
        }
        return checkNotNull(ring.infoOf(tick)) {
            "the ring dropped the snapshot it was just handed at $tick"
        }
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
