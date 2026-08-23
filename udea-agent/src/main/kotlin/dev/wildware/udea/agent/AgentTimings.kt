package dev.wildware.udea.agent

/**
 * Named wall-time measurements the agent surface takes of itself.
 *
 * The digest build records its own cost here, which is what keeps the 0.3ms budget honest
 * once the gate is behind us: a budget that is only ever checked in CI is a budget nobody
 * notices breaking on the machine that matters. The `diag` toolset reads this table through
 * [forEach] and reports it as `system_timings`, so the instrument the engine uses on itself
 * is the same one the agent can see.
 *
 * ## Slots, not a map
 *
 * A caller registers a name once with [slot] and records against the returned index. That is
 * deliberate: the recording call sites are on the simulation thread, one of them inside the
 * budget it is measuring, and a `HashMap` lookup per record would put a hash, a boxed `Long`
 * and possibly a resize on the path. Registration is off the hot path and may do all three.
 *
 * ## Threading
 *
 * Recorded from the simulation thread, read from an HTTP thread. Numbers are plain `Long`s
 * under the monitor for reads that walk the table; a torn read of a timing is not worth an
 * atomic per record, but a torn *walk* would produce a report with the wrong names against
 * the wrong numbers.
 */
public class AgentTimings(
    /** How many distinct names may be registered. */
    public val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val names = arrayOfNulls<String>(capacity)
    private val lastNanos = LongArray(capacity)
    private val totalNanos = LongArray(capacity)
    private val calls = LongArray(capacity)
    private var registered = 0

    /** How many names are registered. */
    public val size: Int get() = synchronized(this) { registered }

    /**
     * The slot for [name], registering it if this is the first time it has been seen.
     *
     * Call once and keep the result. Registering the same name twice returns the same slot,
     * so two subsystems measuring the same thing accumulate rather than shadowing each other.
     *
     * @throws IllegalStateException when [capacity] names are already registered. A table that
     *   silently dropped the next name would report a complete-looking `system_timings` with
     *   the newest instrument missing.
     */
    public fun slot(name: String): Int {
        require(name.isNotBlank()) { "a timing needs a name" }
        synchronized(this) {
            for (index in 0 until registered) {
                if (names[index] == name) return index
            }
            check(registered < capacity) {
                "AgentTimings is full at $capacity names; raise the capacity rather than " +
                    "dropping $name"
            }
            names[registered] = name
            registered++
            return registered - 1
        }
    }

    /** Records that the work at [slot] took [nanos]. Allocation-free. */
    public fun record(slot: Int, nanos: Long) {
        synchronized(this) {
            require(slot in 0 until registered) { "no timing registered at slot $slot" }
            lastNanos[slot] = nanos
            totalNanos[slot] += nanos
            calls[slot]++
        }
    }

    /** The most recent measurement for [name], or -1 if it has never been recorded. */
    public fun lastNanosOf(name: String): Long {
        synchronized(this) {
            for (index in 0 until registered) {
                if (names[index] == name) return if (calls[index] == 0L) NEVER else lastNanos[index]
            }
            return NEVER
        }
    }

    /** Visits every registered timing in registration order. */
    public fun forEach(visitor: TimingVisitor) {
        synchronized(this) {
            for (index in 0 until registered) {
                val name = names[index] ?: continue
                visitor.visit(name, lastNanos[index], totalNanos[index], calls[index])
            }
        }
    }

    override fun toString(): String = "AgentTimings($size/$capacity)"

    public companion object {
        /** Enough for every engine system plus the agent surface, without a resize. */
        public const val DEFAULT_CAPACITY: Int = 64

        /** What [lastNanosOf] answers for a name that has never been recorded. */
        public const val NEVER: Long = -1L
    }
}

/** Callback for [AgentTimings.forEach]. A `fun interface` so a report allocates nothing per row. */
public fun interface TimingVisitor {
    /** One row: what was measured, its last cost, its total cost and how often it ran. */
    public fun visit(name: String, lastNanos: Long, totalNanos: Long, calls: Long)
}
