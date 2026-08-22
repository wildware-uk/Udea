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
 */
public data class EngineConfig(
    /** Simulation frequency. 60Hz fixed (spec 3.3). */
    public val tickRate: Int = SimClock.DEFAULT_TICK_RATE,
    /** Snapshot every Nth tick. 3 at 60Hz is the 20Hz dense cadence. */
    public val snapshotIntervalTicks: Int = 3,
    /** Input send every Nth tick. 2 at 60Hz is the 30Hz input rate. */
    public val inputSendIntervalTicks: Int = 2,
    /** Sparse keyframe cadence backing agent rewind. 60 at 60Hz is one per second. */
    public val keyframeIntervalTicks: Int = 60,
    /** How far back agent rewind may reach. */
    public val rewindWindowTicks: Int = 60 * SimClock.DEFAULT_TICK_RATE,
    /** Ceiling on ticks simulated in one real frame, so a stall cannot spiral. */
    public val maxCatchUpTicks: Int = 5,
    /** Hard ceiling on the snapshot ring (spec 7: ring under 64MB). */
    public val snapshotRingBudgetBytes: Long = 64L * 1024 * 1024,
    /** Hard ceiling on snapshot capture cost (spec 7: capture under 1ms at 1000 entities). */
    public val captureBudgetNanos: Long = 1_000_000,
    /**
     * Seed for every simulation random stream. Two contexts built with different seeds
     * diverge; two built with the same seed replay identically.
     */
    public val seed: Long = 0L,
) {
    init {
        require(tickRate > 0) { "tickRate must be positive, was $tickRate" }
        require(snapshotIntervalTicks > 0) {
            "snapshotIntervalTicks must be positive, was $snapshotIntervalTicks"
        }
        require(inputSendIntervalTicks > 0) {
            "inputSendIntervalTicks must be positive, was $inputSendIntervalTicks"
        }
        require(keyframeIntervalTicks > 0) {
            "keyframeIntervalTicks must be positive, was $keyframeIntervalTicks"
        }
        require(rewindWindowTicks >= 0) {
            "rewindWindowTicks must not be negative, was $rewindWindowTicks"
        }
        require(maxCatchUpTicks > 0) { "maxCatchUpTicks must be positive, was $maxCatchUpTicks" }
        require(snapshotRingBudgetBytes > 0) {
            "snapshotRingBudgetBytes must be positive, was $snapshotRingBudgetBytes"
        }
        require(captureBudgetNanos > 0) {
            "captureBudgetNanos must be positive, was $captureBudgetNanos"
        }
    }
}
