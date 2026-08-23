package dev.wildware.udea.agent.activity

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which agent made a call, as a small dense integer.
 *
 * ## Why an `Int` and not the label
 *
 * [AgentActivityRing] is a parallel-array ring on the simulation thread, and it holds one of
 * these per entry. A `String` there would be a reference the ring keeps alive for the whole
 * window and a per-entry hash on every overlay repaint; an `Int` is a slot in an array. The
 * label an overlay actually prints is looked up once, out of band, through [AgentSessions].
 *
 * Dense from zero on purpose: the overlay colours a session by `raw % palette.size`, so two
 * concurrent sessions are always two different colours rather than two hashes that happened to
 * collide modulo the palette.
 */
@JvmInline
public value class AgentSessionId(public val raw: Int) {

    init {
        require(raw >= 0) { "AgentSessionId is a dense index from zero, was $raw" }
    }

    override fun toString(): String = "AgentSessionId($raw)"

    public companion object {
        /**
         * The session a command carries when nobody said who they were.
         *
         * Not `null` and not "absent": an in-process harness, `SimHarness` and every existing
         * test submit commands with no HTTP request behind them, and an overlay that had to
         * render "no session" as a special case would carry that case into the colour table,
         * the panel header and the marker legend. One real id, labelled `local`, keeps every
         * path the same shape.
         */
        public val LOCAL: AgentSessionId = AgentSessionId(0)
    }
}

/**
 * The intern table from a session label to an [AgentSessionId], and back.
 *
 * ## Bounded, because the label comes from outside
 *
 * `AgentHost` derives a label from the request - a `session` query parameter, or the remote
 * address when there is none - so the set of labels is decided by whoever is talking to the
 * port. An unbounded map there is an unbounded map fed by a client, which is the same defect
 * `AgentBridge` fixed on the command queue. Past [capacity] distinct labels every further one
 * folds onto [AgentSessionId.LOCAL] rather than growing the table: the overlay then attributes
 * those calls to `local`, which is wrong in a cosmetic way, where an `OutOfMemoryError` is
 * wrong in a way that destroys the evidence.
 *
 * ## Threading
 *
 * Written from the HTTP thread (interning on the way in) and read from the render thread (the
 * overlay printing a label), so the map is concurrent and the counter atomic. Nothing here is
 * on the per-tick path: interning happens once per distinct label, and the overlay looks a
 * label up only when the panel is re-formatted, which is on change and not per frame.
 */
public class AgentSessions(
    /** How many distinct labels get their own id. */
    public val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(capacity > 0) { "AgentSessions holds at least one label, was $capacity" }
    }

    private val ids = ConcurrentHashMap<String, Int>()

    private val labels = ConcurrentHashMap<Int, String>()

    private val next = AtomicInteger(1)

    init {
        ids[LOCAL_LABEL] = AgentSessionId.LOCAL.raw
        labels[AgentSessionId.LOCAL.raw] = LOCAL_LABEL
    }

    /** How many distinct labels have been interned, including `local`. */
    public val size: Int get() = labels.size

    /**
     * The id for [label], interning it if this is the first time it has been seen.
     *
     * A blank label is [AgentSessionId.LOCAL]: an empty `session=` parameter is a caller that
     * did not name itself, and giving it an id of its own would put an unlabelled row in the
     * overlay's legend.
     */
    public fun intern(label: String): AgentSessionId {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return AgentSessionId.LOCAL
        ids[trimmed]?.let { return AgentSessionId(it) }
        // `computeIfAbsent` would allocate an id inside the map's own lock and then have to
        // give it back on overflow, leaving a hole in the dense sequence the palette indexes.
        synchronized(this) {
            ids[trimmed]?.let { return AgentSessionId(it) }
            if (labels.size >= capacity) return AgentSessionId.LOCAL
            val id = next.getAndIncrement()
            ids[trimmed] = id
            labels[id] = trimmed
            return AgentSessionId(id)
        }
    }

    /** The label [id] was interned under, or `"?"` for an id this table never issued. */
    public fun label(id: AgentSessionId): String = labels[id.raw] ?: UNKNOWN_LABEL

    override fun toString(): String = "AgentSessions($size/$capacity)"

    public companion object {
        /** What [AgentSessionId.LOCAL] prints as. */
        public const val LOCAL_LABEL: String = "local"

        /** What an id from another table prints as. */
        public const val UNKNOWN_LABEL: String = "?"

        /**
         * 32 distinct sessions.
         *
         * More concurrent agents than a debugging session has ever had, and small enough that a
         * client sending a fresh `session=` on every request costs 32 map entries and then stops
         * costing anything.
         */
        public const val DEFAULT_CAPACITY: Int = 32
    }
}
