package dev.wildware.udea.agent

/**
 * A bounded ring of recent game events, readable without consuming it.
 *
 * ## Two properties, both load-bearing
 *
 * **Bounded**, because a game that runs for an hour with nothing reading it must not grow a
 * log until it dies. The oldest entry is dropped, not the newest: an agent asking what just
 * happened wants the tail.
 *
 * **Non-destructive to read**, because reading state must not consume the log. The reference
 * implementation got this right and it is worth restating why: the bridge polls `/state`
 * repeatedly while it waits for `completedCommandId` to catch up, so a read that drained the
 * ring would delete the events the agent was waiting to see, and the deletion would be timed
 * by the polling loop rather than by anything in the game.
 *
 * ## Threading
 *
 * Written from the simulation thread and read from an HTTP thread, so every access is under
 * the monitor. The critical section is one array write or one bounded walk - never a
 * conversion, never an allocation - so a reader cannot stall a tick.
 */
public class AgentEventRing(
    /** How many entries are kept. */
    public val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "an event ring keeps at least one entry, was $capacity" }
    }

    private val slots: Array<String?> = arrayOfNulls(capacity)

    /** Where the next entry goes. */
    private var writeIndex: Int = 0

    /** Entries currently held, `0..capacity`. */
    private var held: Int = 0

    private var recorded: Long = 0L

    /** Entries currently in the ring. */
    public val size: Int get() = synchronized(this) { held }

    /**
     * Entries recorded since construction, including the ones that have been dropped.
     *
     * The difference between this and [size] is how much an agent has missed, which is the
     * only way it can tell "nothing happened" from "the ring wrapped".
     */
    public val totalRecorded: Long get() = synchronized(this) { recorded }

    /** Appends [message], dropping the oldest entry when the ring is full. */
    public fun record(message: String) {
        synchronized(this) {
            slots[writeIndex] = message
            writeIndex++
            if (writeIndex == capacity) writeIndex = 0
            if (held < capacity) held++
            recorded++
        }
    }

    /**
     * Visits the newest [limit] entries, oldest of those first, and returns how many were
     * visited.
     *
     * Oldest-first within the window because the events tell a story and an agent reads them
     * in order. The visitor is a `fun interface` and the walk allocates nothing, so the Tier-0
     * digest can render the ring inside its zero-allocation budget.
     */
    public fun forEachRecent(limit: Int, visitor: AgentEventVisitor): Int {
        require(limit >= 0) { "limit must not be negative, was $limit" }
        synchronized(this) {
            val visiting = if (limit < held) limit else held
            // The window ends at the newest entry and starts `visiting` back from it.
            var cursor = writeIndex - visiting
            if (cursor < 0) cursor += capacity
            var visited = 0
            while (visited < visiting) {
                val message = slots[cursor]
                if (message != null) visitor.visit(message)
                cursor++
                if (cursor == capacity) cursor = 0
                visited++
            }
            return visiting
        }
    }

    /**
     * Every held entry, oldest first, as a list.
     *
     * Allocating, and therefore never called from the digest build: this is for tools and
     * tests, which are off the per-tick path.
     */
    public fun toList(): List<String> {
        val out = ArrayList<String>(size)
        forEachRecent(capacity) { out.add(it) }
        return out
    }

    /** Drops every entry. [totalRecorded] is deliberately not reset - history happened. */
    public fun clear() {
        synchronized(this) {
            slots.fill(null)
            writeIndex = 0
            held = 0
        }
    }

    override fun toString(): String = "AgentEventRing($size/$capacity, $totalRecorded recorded)"

    public companion object {
        /**
         * 200 entries, carried forward from the reference implementation.
         *
         * It is a tick or two of a busy game and about 8KB of strings: enough that an agent
         * polling after a command sees the whole consequence, small enough that an unread ring
         * is not a leak.
         */
        public const val DEFAULT_CAPACITY: Int = 200
    }
}

/** Callback for [AgentEventRing.forEachRecent]. A `fun interface` so the walk allocates nothing. */
public fun interface AgentEventVisitor {
    /** Receives one event message. */
    public fun visit(message: String)
}
