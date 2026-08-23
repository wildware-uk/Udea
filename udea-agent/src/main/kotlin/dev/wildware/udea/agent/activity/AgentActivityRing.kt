package dev.wildware.udea.agent.activity

import dev.wildware.udea.agent.AgentCommand

/**
 * How a tool call ended, as one byte in the ring.
 *
 * Four cases and not two: `ok`/`failed` loses the two distinctions an overlay most needs to
 * show. A call still **running** is the one a human most wants to see - a tool that wedges is
 * invisible in a ring written only on completion - and an **unknown** tool is a mis-wired agent
 * rather than a broken game, which is a different problem with a different fix.
 */
public enum class AgentOutcome {
    /** Still running. What an entry holds between [AgentActivityRing.begin] and [AgentActivityRing.complete]. */
    RUNNING,

    /** The tool returned a value. */
    OK,

    /** The tool refused, or threw, and the dispatcher turned it into an `ok:false`. */
    FAILED,

    /** No tool of that name is registered. */
    UNKNOWN,
}

/**
 * The bounded, non-destructive log of what the agent has been doing (spec 3.7, issue #157).
 *
 * ## What it is for, and the property that makes it correct
 *
 * A human watching a windowed instance sees an overlay drawn from this ring. **The agent never
 * sees it.** That is a correctness requirement rather than a nicety: an agent doing visual
 * verification - capture, act, capture, diff - that could see its own tool history would read
 * its own narration changing between two frames as *the game* changing.
 *
 * The exclusion is enforced in two places and neither of them is a flag:
 *
 * - the overlay draws onto a `ScreenTarget`, which no capture reads (`udea-render`'s type
 *   split);
 * - and nothing in this class is rendered into the `/state` digest. `ActivityIsNotAgentVisibleTest`
 *   asserts that, because "we did not add it to the digest" is a property that decays the first
 *   time somebody adds a field to `StateDigest` and reaches for the nearest bridge member.
 *
 * ## Never snapshotted, and why that is the interesting half
 *
 * This ring lives on [dev.wildware.udea.agent.AgentBridge], which is not a component, not in a
 * `FieldStore` and not in the snapshot ring. So a rewind moves the simulation back sixty
 * seconds and **does not** rewrite the human's view of what the agent did - which is right: the
 * agent really did make those calls, and a panel that un-made them as the world rewound would
 * be lying about the only history the human has.
 *
 * ## Bounded, non-destructive, allocation-free to read
 *
 * The same three properties as [dev.wildware.udea.agent.AgentEventRing], for the same reasons,
 * and implemented the same way: parallel primitive arrays, a monitor around one array write or
 * one bounded walk, and a walk that hands out a reusable cursor rather than a list.
 *
 * Recording allocates a small argument digest per tool call, and that is deliberate. It is not
 * on a per-tick path - tool calls arrive at the rate an agent issues them - and pre-formatting
 * once at record time is exactly what keeps the sixty-times-a-second side free of it.
 */
public class AgentActivityRing(
    /** How many calls are kept. */
    public val capacity: Int = DEFAULT_CAPACITY,
    /** Longest argument digest kept, in characters. */
    public val digestLimit: Int = DEFAULT_DIGEST_LIMIT,
) {

    init {
        require(capacity > 0) { "an activity ring keeps at least one call, was $capacity" }
        require(digestLimit > 0) { "digestLimit must be positive, was $digestLimit" }
    }

    private val names = arrayOfNulls<String>(capacity)
    private val digests = arrayOfNulls<String>(capacity)
    private val commandIds = LongArray(capacity)
    private val ticks = LongArray(capacity)
    private val durations = LongArray(capacity)
    private val outcomes = ByteArray(capacity)
    private val sessions = IntArray(capacity)
    private val anchorKinds = ByteArray(capacity)
    private val anchorIds = IntArray(capacity)
    private val anchorXs = FloatArray(capacity)
    private val anchorYs = FloatArray(capacity)

    private var writeIndex: Int = 0
    private var held: Int = 0
    private var revision: Long = 0L

    /** Entries currently in the ring. */
    public val size: Int get() = synchronized(this) { held }

    /**
     * Increments on every write, including an in-place completion of an entry already recorded.
     *
     * The overlay's re-format gate. A frame-by-frame diff of the ring's contents would be the
     * per-frame walk the pre-formatting exists to avoid, and `size` alone stops moving once the
     * ring is full - so a full ring would freeze the panel on the first sixty-four calls.
     */
    public val version: Long get() = synchronized(this) { revision }

    /**
     * Records a call that has just started, and returns the slot it went into.
     *
     * Recorded on entry rather than on completion so that a tool which throws, wedges or takes
     * two seconds is *visible while it is happening*. A ring written only on completion shows a
     * human nothing at all during the one call they most want to see.
     *
     * @param anchor the rule derived from the tool's declared arguments; [AnchorRule.NONE] for a
     *   tool that names nothing an overlay can point at.
     * @return the slot, to be passed to [complete].
     */
    public fun begin(
        command: AgentCommand,
        tick: Long,
        session: AgentSessionId,
        anchor: AnchorRule,
    ): Int {
        // Resolved outside the monitor: `AgentCommand.args` is immutable and the scratch arrays
        // are locals, so the anchor and the digest cost the lock nothing.
        val id = IntArray(1)
        val coords = FloatArray(2)
        val kind = anchor.resolve(command, id, coords)
        val digest = digest(command)
        synchronized(this) {
            val slot = writeIndex
            names[slot] = command.name
            digests[slot] = digest
            commandIds[slot] = command.id
            ticks[slot] = tick
            durations[slot] = 0L
            outcomes[slot] = AgentOutcome.RUNNING.ordinal.toByte()
            sessions[slot] = session.raw
            anchorKinds[slot] = kind.ordinal.toByte()
            anchorIds[slot] = id[0]
            anchorXs[slot] = coords[0]
            anchorYs[slot] = coords[1]
            writeIndex++
            if (writeIndex == capacity) writeIndex = 0
            if (held < capacity) held++
            revision++
            return slot
        }
    }

    /**
     * Completes the call recorded in [slot].
     *
     * A no-op when the slot has since been overwritten by a later call: a tool that outlives
     * [capacity] further calls has lost its row, and writing a duration into whatever now owns
     * that slot would corrupt an unrelated entry. The guard compares [commandId] and not the
     * slot, which is what makes it exact rather than probabilistic.
     */
    public fun complete(slot: Int, commandId: Long, outcome: AgentOutcome, durationNanos: Long) {
        require(outcome != AgentOutcome.RUNNING) { "complete() takes a terminal outcome" }
        require(durationNanos >= 0L) { "durationNanos must not be negative, was $durationNanos" }
        synchronized(this) {
            if (slot < 0 || slot >= capacity) return
            if (commandIds[slot] != commandId) return
            outcomes[slot] = outcome.ordinal.toByte()
            durations[slot] = durationNanos
            revision++
        }
    }

    /**
     * Visits the newest [limit] calls, **newest first**, and returns how many were visited.
     *
     * Newest first, unlike [dev.wildware.udea.agent.AgentEventRing], and for the opposite
     * reason: events tell a story and are read in order, while an activity panel is a stack of
     * "what just happened" whose top line is the one a human looks at. Rendering it oldest-first
     * and reversing would need a list.
     *
     * The visitor is handed a **reused cursor owned by this ring**. It is valid only for the
     * duration of one `visit` call: keeping it and reading it afterwards reads whatever entry
     * the ring points at next, and copying a field out is the supported way to keep one. That is
     * what makes this walk allocation-free, which matters because the overlay walks it whenever
     * the panel re-formats.
     */
    public fun forEachRecent(limit: Int, visitor: AgentActivityVisitor): Int {
        require(limit >= 0) { "limit must not be negative, was $limit" }
        synchronized(this) {
            val visiting = if (limit < held) limit else held
            var cursor = writeIndex
            var visited = 0
            while (visited < visiting) {
                cursor--
                if (cursor < 0) cursor += capacity
                if (names[cursor] != null) {
                    view.slot = cursor
                    visitor.visit(view)
                }
                visited++
            }
            return visiting
        }
    }

    /** Drops every entry. */
    public fun clear() {
        synchronized(this) {
            names.fill(null)
            digests.fill(null)
            writeIndex = 0
            held = 0
            revision++
        }
    }

    override fun toString(): String = "AgentActivityRing($size/$capacity, rev $version)"

    /**
     * The reusable cursor handed to a visitor.
     *
     * An inner class over the ring's arrays rather than a copy of one entry: a copy would be an
     * allocation per entry per walk, which is exactly what this design exists to avoid.
     */
    public inner class View internal constructor() {

        internal var slot: Int = 0

        /** The tool that was called. */
        public val toolName: String get() = names[slot] ?: ""

        /** The arguments, pre-formatted and capped at [digestLimit]. */
        public val argDigest: String get() = digests[slot] ?: ""

        /** The command id, as `completedCommandId` reports it. */
        public val commandId: Long get() = commandIds[slot]

        /** The simulation tick the call was applied on. */
        public val tick: Long get() = ticks[slot]

        /** Wall-clock nanoseconds the call took, or `0` while it is still running. */
        public val durationNanos: Long get() = durations[slot]

        /** How it ended. */
        public val outcome: AgentOutcome get() = OUTCOMES[outcomes[slot].toInt()]

        /** Who made the call. */
        public val session: AgentSessionId get() = AgentSessionId(sessions[slot])

        /** What the call was about. */
        public val anchorKind: AnchorKind get() = ANCHORS[anchorKinds[slot].toInt()]

        /** The packed NetId, meaningful only when [anchorKind] is [AnchorKind.ENTITY]. */
        public val anchorNetId: Int get() = anchorIds[slot]

        /** World x, meaningful only when [anchorKind] is [AnchorKind.POINT]. */
        public val anchorX: Float get() = anchorXs[slot]

        /** World y, meaningful only when [anchorKind] is [AnchorKind.POINT]. */
        public val anchorY: Float get() = anchorYs[slot]

        override fun toString(): String = "$toolName($argDigest) $outcome"
    }

    private val view = View()

    /**
     * `name=value` pairs, in the order they arrived, truncated to [digestLimit] with an ellipsis.
     *
     * Truncated rather than refused, unlike `agent_say`: an argument list is not something a
     * caller chose the length of for the overlay's benefit, and refusing a tool call because its
     * arguments would not fit a debug panel would be the panel deciding what the agent may do.
     */
    private fun digest(command: AgentCommand): String {
        if (command.args.isEmpty()) return ""
        val out = StringBuilder(digestLimit + ELLIPSIS.length)
        for (entry in command.args) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(entry.key).append('=').append(entry.value)
            if (out.length > digestLimit) {
                out.setLength(digestLimit)
                out.append(ELLIPSIS)
                break
            }
        }
        return out.toString()
    }

    public companion object {

        /**
         * 64 calls.
         *
         * Deeper than any panel shows - the corner panel renders a handful - so that a human who
         * turns the verbosity up mid-session sees history rather than only what happened since
         * they pressed the key. A third of `AgentEventRing`'s depth, because a tool call is a
         * far rarer thing than a game event.
         */
        public const val DEFAULT_CAPACITY: Int = 64

        /** 80 characters of arguments: one panel line at a readable font size. */
        public const val DEFAULT_DIGEST_LIMIT: Int = 80

        /** What a truncated digest ends with. */
        public const val ELLIPSIS: String = "..."

        /** Hoisted: `entries.toTypedArray()` would allocate on every cursor read. */
        private val OUTCOMES: Array<AgentOutcome> = AgentOutcome.entries.toTypedArray()

        /** Hoisted, as [OUTCOMES]. */
        private val ANCHORS: Array<AnchorKind> = AnchorKind.entries.toTypedArray()
    }
}

/** Callback for [AgentActivityRing.forEachRecent]. A `fun interface` so the walk allocates nothing. */
public fun interface AgentActivityVisitor {
    /**
     * Receives one recorded call.
     *
     * @param call a cursor valid **only for this call**. Copy what you need out of it; keeping
     *   the reference reads whatever entry the ring points at next.
     */
    public fun visit(call: AgentActivityRing.View)
}
