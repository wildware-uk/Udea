package dev.wildware.udea.core

/**
 * The universal unit of simulation time (spec 5, "Time").
 *
 * Everything the simulation measures is denominated in ticks: GAS durations, snapshot ring
 * slots, replication baselines, input stamps and `step(n)`. Seconds exist only in
 * `udea-render` and audio, so no `udea-core` type converts a [Tick] to seconds — that is
 * deliberate. Producing a seconds value is [SimClock.time]'s job and nothing else's.
 *
 * A [Tick] is a `Long` count from the start of the simulation, so it never wraps in any
 * plausible session: a 60Hz simulation would need billions of years to overflow.
 */
@JvmInline
public value class Tick(public val value: Long) : Comparable<Tick> {

    /** The tick [ticks] ticks after this one. */
    public operator fun plus(ticks: Long): Tick = Tick(value + ticks)

    /** The tick [ticks] ticks before this one. */
    public operator fun minus(ticks: Long): Tick = Tick(value - ticks)

    public operator fun inc(): Tick = Tick(value + 1)

    public operator fun dec(): Tick = Tick(value - 1)

    override fun compareTo(other: Tick): Int = value.compareTo(other.value)

    /**
     * The half-open range `[this, end)`, iterated in ascending tick order.
     *
     * Half-open because a tick range is a span of *steps*, not of instants: replaying
     * `a until b` runs exactly `b - a` steps and leaves the clock reading `b`.
     */
    public infix fun until(end: Tick): TickRange = TickRange(this, end)

    /**
     * How many ticks have elapsed since [earlier]; negative if [earlier] is in the future.
     *
     * Deliberately not an operator: the difference of two ticks is a *duration*, and giving
     * it a distinct name stops `a - b` silently yielding a [Tick] that actually means
     * "a number of ticks".
     */
    public fun ticksSince(earlier: Tick): Long = value - earlier.value

    override fun toString(): String = "t$value"

    public companion object {
        /** The first tick of a simulation. A freshly built [SimClock] reads this. */
        public val ZERO: Tick = Tick(0)
    }
}

/** The half-open tick span produced by [Tick.until]. */
public data class TickRange(
    public val start: Tick,
    public val endExclusive: Tick,
) : Iterable<Tick> {

    /** Number of ticks in the span; zero when the range is empty or inverted. */
    public val count: Long
        get() = if (endExclusive.value > start.value) endExclusive.value - start.value else 0L

    public val isEmpty: Boolean get() = count == 0L

    public operator fun contains(tick: Tick): Boolean = tick >= start && tick < endExclusive

    override fun iterator(): Iterator<Tick> = TickIterator(start, endExclusive)

    private class TickIterator(from: Tick, private val endExclusive: Tick) : Iterator<Tick> {
        private var next: Long = from.value

        override fun hasNext(): Boolean = next < endExclusive.value

        override fun next(): Tick {
            if (!hasNext()) throw NoSuchElementException("Tick range exhausted at $endExclusive")
            return Tick(next++)
        }
    }
}
