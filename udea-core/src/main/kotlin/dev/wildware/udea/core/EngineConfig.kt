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
 * The snapshot cadence is the one worth naming, because its absence is a functional hole and
 * not only a tidiness one: nothing in an assembled game drives `TimeTravel.captureNow`, so
 * the ring is empty and every rewind returns `tick_out_of_ring`. See the "who drives capture"
 * note on `TimeTravel`. When Phase 1 gives that an owner, the knob comes back **in the same
 * commit as its consumer**, which is the only order that keeps this KDoc true.
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
) {
    init {
        require(tickRate > 0) { "tickRate must be positive, was $tickRate" }
        require(maxCatchUpTicks > 0) { "maxCatchUpTicks must be positive, was $maxCatchUpTicks" }
    }
}
