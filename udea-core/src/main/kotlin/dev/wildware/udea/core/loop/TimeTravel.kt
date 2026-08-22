package dev.wildware.udea.core.loop

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

/** Whether a ring slot is inside the dense rollback window or the sparse rewind window. */
public enum class SnapshotKind {

    /** Every tick within the dense window. What Phase 4's prediction rollback restores from. */
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
}

/** What [TimeTravel.restoreNearestAtOrBefore] did. */
public sealed interface RestoreOutcome {

    /** The world now holds the snapshot taken at [restoredTick]. */
    public data class Restored(public val restoredTick: Tick) : RestoreOutcome

    /** Nothing was applied and the world is untouched. */
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

    /** Nothing happened and the world is untouched. */
    public data class Failed(
        public val failure: RewindFailure,
        /** Human-readable detail. Never parsed; [RewindFailure.code] is the contract. */
        public val detail: String,
    ) : RewindResult
}
