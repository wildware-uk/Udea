package dev.wildware.udea.core.snapshot

/**
 * The Phase 0 performance budgets, in one place so changing one is a reviewed diff.
 *
 * Spec 6's Phase 0 exit and spec 7's risk row both state these as **hard CI gates, not
 * aspirations**, and give the reason: one structure carries time travel, replication
 * baselines and rollback, so if capture allocates then three features degrade at once. A
 * budget nobody enforces is missed silently somewhere around Phase 3 and found in Phase 5.
 *
 * They live here rather than inside the tests that assert them because a number buried in a
 * test is a number that gets nudged. Here, moving one is a diff a reviewer sees.
 *
 * ## When the gate fails on slower hardware
 *
 * The documented remedy is [SnapshotRing.degrade]: raise `sparseInterval`, keeping the full
 * sixty-second rewind window at lower keyframe density, and pay a few extra bare steps when
 * landing on an unaligned tick. Spec 7 fixes that policy — "if the budget is missed, degrade
 * the sparse cadence rather than dropping the feature".
 *
 * **Do not loosen a number here and do not disable the task.** These are Phase 0 exit
 * criteria; a budget that moves when it is missed measures nothing at all. If CI hardware
 * turns out to vary more than the budget, the fix named in the plan is to pin the runner
 * class and record a ratio against a calibration run — not to widen the number.
 */
public object SnapshotBudgets {

    /** The entity count every capture budget is quoted at (spec 7). */
    public const val CAPTURE_ENTITIES: Int = 1_000

    /**
     * Median wall time for one capture of [CAPTURE_ENTITIES] entities.
     *
     * Median rather than mean, and after a warm-up: JIT and GC variance are handled by
     * warming the path, never by widening the budget.
     */
    public const val CAPTURE_NANOS: Long = 1_000_000L

    /**
     * Bytes a warm capture may allocate. Zero, and it means zero.
     *
     * Not "small": a capture that allocates one object per entity allocates 60 000 a second at
     * 60Hz, and the GC pause that follows is a frame the simulation does not get.
     */
    public const val CAPTURE_ALLOCATED_BYTES: Long = 0L

    /** Hard ceiling on the whole ring: 64MB (spec 7). */
    public const val RING_BYTES: Long = 64L * 1024 * 1024

    /** Entities the assembled-loop gate runs (spec 6, the Phase 0 demo). */
    public const val LOOP_ENTITIES: Int = 200

    /** Ticks the assembled-loop gate runs. */
    public const val LOOP_TICKS: Int = 600

    /**
     * Median wall time for [LOOP_TICKS] ticks at [LOOP_ENTITIES] entities.
     *
     * The headline Phase 0 number. Every component can pass its own gate while the assembled
     * `Simulation.step()` misses this one, which is why it is measured separately.
     */
    public const val LOOP_NANOS: Long = 50_000_000L

    /** Bytes the assembled loop may allocate in steady state. Zero, for the same reason. */
    public const val LOOP_ALLOCATED_BYTES: Long = 0L
}
