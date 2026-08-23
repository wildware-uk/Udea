package dev.wildware.udea.core

/**
 * Every knob the headless kernel reads, in one immutable value.
 *
 * Replaces the ad-hoc `GameScreen.gameConfig` read: a system reaches this through
 * [GameContext.config] and never through a global, so two simulations in one JVM can be
 * configured differently.
 *
 * Cadences are expressed in **ticks**, not hertz, because the tick is the only unit the
 * simulation has (spec 5, "Time"). The hertz figures from spec 3.3 are the defaults.
 *
 * **Every field here has a consumer.** It used to carry six more — a snapshot cadence, an
 * input-send cadence, a keyframe cadence, a rewind window, a ring byte budget and a capture
 * budget — and not one of them was read by anything outside this file. A configuration knob
 * with no consumer is worse than a missing feature: it reads as though the cadence is
 * configured, so `snapshotIntervalTicks = 6` looks like a decision and changes nothing, and
 * an assembled game's snapshot ring stays empty while the type says otherwise. Three of the
 * six had a *real* owner elsewhere and were duplicated here in name only —
 * `RingConfig.denseTicks`, `RingConfig.sparseInterval` and `RingConfig.budgetBytes` are the
 * ones `SnapshotRing` actually reads, and `SnapshotBudgets` owns the capture ceiling.
 *
 * [snapshotIntervalTicks] is back, and it is back **with** its consumer:
 * `SnapshotTimeTravel` reads it in its constructor and `WorldSimulation.step` acts on it once
 * per tick. Delete the driver and this field must go with it again.
 */
public data class EngineConfig(
    /** Simulation frequency. 60Hz fixed (spec 3.3). */
    public val tickRate: Int = SimClock.DEFAULT_TICK_RATE,
    /** Ceiling on ticks simulated in one real frame, so a stall cannot spiral. */
    public val maxCatchUpTicks: Int = 5,
    /**
     * Seed for every simulation random stream. Two contexts built with different seeds
     * diverge; two built with the same seed replay identically.
     */
    public val seed: Long = 0L,
    /**
     * How often the simulation captures a snapshot, in ticks. `3` is spec 3.3's 20Hz at 60Hz.
     *
     * Read by `SnapshotTimeTravel`, which turns it into `TimeTravel.captureIfDue`, which
     * `WorldSimulation.step` calls after every tick. This is the *cadence*, not the switch: a
     * simulation built with no `TimeTravelFactory` has no ring at all and captures nothing
     * whatever this says, which is what keeps a dedicated server from paying for a 64MB ring
     * nobody reads.
     *
     * `0` disables the cadence for a simulation that **does** have a ring — a host that wants
     * to place every keyframe itself through `TimeControl.snapshot()`. Negative is refused
     * rather than treated as off, because `-1` is a typo and silence would hide it.
     */
    public val snapshotIntervalTicks: Int = DEFAULT_SNAPSHOT_INTERVAL_TICKS,
) {
    init {
        require(tickRate > 0) { "tickRate must be positive, was $tickRate" }
        require(maxCatchUpTicks > 0) { "maxCatchUpTicks must be positive, was $maxCatchUpTicks" }
        require(snapshotIntervalTicks >= 0) {
            "snapshotIntervalTicks must not be negative, was $snapshotIntervalTicks; 0 turns " +
                "the loop's capture cadence off"
        }
    }

    public companion object {
        /** Every third tick: 20Hz snapshots against a 60Hz simulation (spec 3.3). */
        public const val DEFAULT_SNAPSHOT_INTERVAL_TICKS: Int = 3
    }
}
