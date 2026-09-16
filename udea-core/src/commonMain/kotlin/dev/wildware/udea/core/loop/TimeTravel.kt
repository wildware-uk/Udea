package dev.wildware.udea.core.loop

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.Tick

/**
 * The half of [TimeControl] that needs a snapshot ring behind it.
 *
 * Declared here and implemented in `dev.wildware.udea.core.snapshot`, for the same reason
 * [Presentation] is declared here and implemented in `udea-render`: [TimeControl] is the
 * agent-facing facade over *time*, and it must not depend on the ring, the world or the
 * component registry to offer `pause` and `step`. A `TimeControl` built without one is a
 * complete, working object — every time-travel call returns [RewindFailure.NoSnapshotRing]
 * rather than throwing — which is what lets the loop tests drive it with no world at all.
 *
 * The direction of the dependency is the point. The snapshot package already depends on this
 * one, for [SimBarrier] and [BarrierAction]; if the vocabulary below lived over there, the two
 * packages would import each other.
 */
public interface TimeTravel {

    /** The tick the simulation is about to run. Same value as `SimClock.tick`. */
    public val currentTick: Tick

    /** Captures the world as it stands and stores it in the ring. */
    public fun captureNow(): SnapshotInfo

    /**
     * Captures this tick if the cadence says it is due, and allocates nothing when it is.
     *
     * The per-tick entry point: [Simulation.step] calls it once, after the clock has advanced,
     * on every tick of every simulation that was built with a ring. [captureNow] cannot take
     * that job because it returns a [SnapshotInfo] — one object per captured tick, on the one
     * path `SnapshotBudgets.LOOP_ALLOCATED_BYTES` gates at zero bytes.
     *
     * Whether a tick is due is the implementation's to decide, not the loop's, so that the
     * cadence, the ring and the retention windows are configured in one place instead of the
     * loop holding half of the policy. `SnapshotTimeTravel` reads
     * `EngineConfig.snapshotIntervalTicks`.
     *
     * @return true if a new snapshot entered the ring. False means the tick was not due, the
     *   cadence is off, or the ring already held this tick — never that a capture failed: a
     *   capture that cannot complete throws, because it is a defect and not a condition.
     */
    public fun captureIfDue(): Boolean

    /** Every snapshot the ring is holding, oldest first. */
    public fun listSnapshots(): List<SnapshotInfo>

    /**
     * Restores the newest snapshot at or before [target], immediately.
     *
     * Only ever called with the loop paused and the simulation at a tick boundary, which is
     * what makes an immediate restore safe rather than a torn world. The caller then steps
     * forward to land exactly on [target].
     */
    public fun restoreNearestAtOrBefore(target: Tick): RestoreOutcome

    /**
     * Whether the asset graph changed between [since] and now.
     *
     * An agent that rewinds past a hot-reload is looking at a world whose blueprints have
     * since changed, and a rewind that did not say so would have it comparing screenshots of
     * two different games. Answered by an injected [AssetGraphHistory], which is
     * [AssetGraphHistory.Unchanged] until the asset epic lands (spec 3.6).
     */
    public fun assetGraphChangedSince(since: Tick): Boolean
}

/**
 * When the asset graph last changed.
 *
 * An interface with one method rather than a field on [TimeTravel], because the answer comes
 * from `udea-assets` and the kernel must not know that module exists. [Unchanged] is the
 * complete, correct implementation for a simulation with no hot-reload — which is every
 * simulation until Phase 2 — rather than a stub.
 */
public fun interface AssetGraphHistory {

    /** True when the asset graph changed at any tick after [since]. */
    public fun changedSince(since: Tick): Boolean

    public companion object {
        /** A graph that never changes: a build-time-only asset pipeline, and every test. */
        public val Unchanged: AssetGraphHistory = AssetGraphHistory { false }
    }
}

/**
 * Whether a ring slot is inside the dense rollback window or the sparse rewind window.
 *
 * Both windows describe *captured* ticks.
 *
 * ## Who drives capture
 *
 * [WorldSimulation.step] does, once per tick, through [TimeTravel.captureIfDue], on the
 * cadence `EngineConfig.snapshotIntervalTicks` names — every third tick, which is spec 3.3's
 * 20Hz against a 60Hz simulation. So the dense window holds one slot in three rather than one
 * per tick, and a rewind to a tick in between lands by restoring the nearest keyframe and
 * stepping forward, exactly as it does outside the window.
 *
 * ## When capture is active
 *
 * Only when the game was built with a [TimeTravelFactory]. A [dev.wildware.udea.core.module.UdeaGameDef]
 * without one produces a simulation whose `travel` is `null`: no ring is ever allocated, the
 * per-tick cost is one null check, and `TimeControl` answers every time-travel call with
 * [RewindFailure.NoSnapshotRing]. That is the answer to "does a dedicated server with no
 * observer pay for a 64MB ring every match" — it does not, and it does not because the ring
 * does not exist, rather than because something remembered to switch it off.
 *
 * A host that wants history attaches one; the agent surface is the obvious caller, and so is
 * a server that wants rollback for prediction in Phase 4. Setting
 * `EngineConfig.snapshotIntervalTicks = 0` on a game that *does* have a ring turns the loop's
 * cadence off while leaving `TimeControl.snapshot()` working, for a host that places keyframes
 * itself.
 */
public enum class SnapshotKind {

    /**
     * Every *captured* tick within the dense window — one in three at the default cadence.
     * What Phase 4's prediction rollback restores from.
     */
    Dense,

    /** Every `sparseInterval`th tick further back. What an agent's sixty-second rewind lands on. */
    Sparse,
}

/**
 * One snapshot the ring is holding, as an agent sees it.
 *
 * Deliberately not the snapshot itself: `listSnapshots` is an MCP tool result, and handing an
 * agent a live pooled buffer that the ring may recycle on the next tick would be handing it a
 * dangling reference.
 */
public data class SnapshotInfo(
    /** The tick the snapshot was captured at. */
    public val tick: Tick,
    /** Which retention window it is in. */
    public val kind: SnapshotKind,
    /** Bytes of backing storage it occupies, as the ring's budget counts it. */
    public val sizeBytes: Long,
)

/** Why a time-travel call could not be honoured. Typed, because an agent has to branch on it. */
public enum class RewindFailure(
    /** The stable code an MCP tool result carries. Never a message; messages are not contracts. */
    public val code: String,
) {

    /** The target tick is older than the oldest snapshot the ring still holds. */
    TickOutOfRing("tick_out_of_ring"),

    /**
     * The snapshot belongs to a different scene. Load the scene first (spec 5, scene
     * lifecycle) — entity ids minted in one scene mean nothing in another.
     */
    SceneMismatch("scene_mismatch"),

    /**
     * This [TimeControl] was built with no [TimeTravel] behind it, so there is no ring to
     * rewind through. A headless loop test is the normal case.
     */
    NoSnapshotRing("no_snapshot_ring"),

    /**
     * The keyframe was found and submitted, and applying it threw.
     *
     * The only failure here that does **not** leave the world untouched: [SimBarrier.drain]
     * catches, logs and continues past a throwing [BarrierAction] by design, so a restore that
     * died half way leaves a world that is neither the snapshot nor the tick it came from, and
     * whose clock may never have been moved. Reporting it is the whole point — the alternative
     * is `RewindResult.Rewound(tick = target)` for a world that is not at `target`, and an
     * agent that then reasons about a tick it is not at.
     *
     * Not recoverable by asking for less. The log line named by `SimBarrier` carries the
     * failing action and the exception.
     */
    RestoreFailed("restore_failed"),
}

/** What [TimeTravel.restoreNearestAtOrBefore] did. */
public sealed interface RestoreOutcome {

    /** The world now holds the snapshot taken at [restoredTick]. */
    public data class Restored(public val restoredTick: Tick) : RestoreOutcome

    /**
     * The rewind did not land.
     *
     * The world is untouched for every [RewindFailure] except [RewindFailure.RestoreFailed],
     * which means the restore was applied and threw part way through and the world is in an
     * undefined state.
     */
    public data class Refused(
        public val failure: RewindFailure,
        /** The scene the snapshot belongs to, when [failure] is a scene mismatch. */
        public val snapshotScene: SceneId? = null,
        /** The scene that is active, when [failure] is a scene mismatch. */
        public val activeScene: SceneId? = null,
    ) : RestoreOutcome
}

/**
 * What `TimeControl.rewind` did.
 *
 * A result rather than an exception, because every one of these is a normal answer to a
 * reasonable question from an agent — "rewind further than the ring goes" is not a bug, it is
 * a `tick_out_of_ring` the agent should handle by asking for less.
 */
public sealed interface RewindResult {

    /** The rewind landed. */
    public data class Rewound(
        /** The tick the simulation is now at. Exactly the tick that was asked for. */
        public val tick: Tick,
        /** The keyframe the restore came from; at or before [tick]. */
        public val restoredFromTick: Tick,
        /** Bare steps run to close the gap between [restoredFromTick] and [tick]. */
        public val steppedForward: Int,
        /** Whether the asset graph changed since [restoredFromTick]. See [TimeTravel]. */
        public val assetGraphChangedSince: Boolean,
    ) : RewindResult

    /**
     * The rewind did not land, and [failure] says why.
     *
     * The world is untouched for every failure except [RewindFailure.RestoreFailed] — see
     * that constant.
     *
     * **The loop is left as it was found**, again except for [RewindFailure.RestoreFailed],
     * where the world is half-restored and staying paused is the safe answer. A refused
     * rewind is a question that was asked and answered; halting a running game as a side
     * effect of asking it would make probing the ring a destructive act.
     */
    public data class Failed(
        public val failure: RewindFailure,
        /** Human-readable detail. Never parsed; [RewindFailure.code] is the contract. */
        public val detail: String,
    ) : RewindResult
}

/**
 * Builds the [TimeTravel] a simulation captures into, once its world exists.
 *
 * A factory and not an instance, for the same reason [dev.wildware.udea.core.host.PresentationFactory]
 * is one: a snapshot ring needs the `World` and the `GameContext`, and neither exists until
 * `UdeaGameDef.build()` has run. Handing the definition a factory keeps [WorldSimulation]'s
 * ring a constructor argument rather than a field something sets afterwards — a simulation
 * either has history for its whole life or never has any, and there is no window in which it
 * is half wired.
 *
 * It lives here rather than in `dev.wildware.udea.core.snapshot` because `UdeaGameDef` names
 * it, and the kernel's module package must not depend on the ring: the concrete factory is
 * `snapshotTimeTravel(...)` over there, and a host that has a generated `ComponentRegistry`
 * is what supplies it.
 */
public fun interface TimeTravelFactory {

    /** Builds the ring for [world], which has just been configured against [ctx]. */
    public fun create(ctx: GameContext, world: World): TimeTravel
}
