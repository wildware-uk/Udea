package dev.wildware.udea.core

/**
 * The simulation's authoritative clock.
 *
 * [time] is **derived** (`tick * dt`), never accumulated. The old engine ran
 * `time += delta` once per frame in `UdeaGameManager`, which had three separate defects:
 * float error compounded without bound, two machines that had run the same number of ticks
 * disagreed about the time, and a rewind could not restore the clock without also restoring
 * the accumulator. Deriving removes all three: the clock is a pure function of [tick], so a
 * snapshot restore restores time exactly, and `time` after N ticks is identical everywhere.
 *
 * There is deliberately no `update(delta: Float)` and no seconds-denominated setter. The
 * clock moves a whole tick at a time; turning real elapsed time into a whole number of
 * ticks is the simulation loop's job, and its accumulator is not simulation state.
 */
public class SimClock(public val tickRate: Int = DEFAULT_TICK_RATE) {

    init {
        require(tickRate > 0) { "tickRate must be positive, was $tickRate" }
    }

    /** Seconds of simulated time per tick. Constant for the life of the clock. */
    public val dt: Float = 1f / tickRate

    /** The tick about to be simulated. Only the kernel advances it. */
    public var tick: Tick = Tick.ZERO
        internal set

    /**
     * Simulated seconds elapsed, derived from [tick] on every read. Never accumulated.
     *
     * `Double` rather than `Float` so a long session keeps sub-millisecond resolution.
     */
    public val time: Double get() = tick.value * dt.toDouble()

    /** Advances by exactly one tick. */
    internal fun advance() {
        tick = Tick(tick.value + 1)
    }

    /** Moves the clock to [target]. Used by snapshot restore and by `step(n)`. */
    internal fun moveTo(target: Tick) {
        tick = target
    }

    override fun toString(): String = "SimClock(tick=$tick, tickRate=$tickRate)"

    public companion object {
        /** 60Hz fixed simulation (spec 3.3). */
        public const val DEFAULT_TICK_RATE: Int = 60
    }
}
