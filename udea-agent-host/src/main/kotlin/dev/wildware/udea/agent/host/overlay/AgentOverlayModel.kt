package dev.wildware.udea.agent.host.overlay

import dev.wildware.udea.agent.activity.AgentActivityRing
import dev.wildware.udea.agent.activity.AgentNarration
import dev.wildware.udea.agent.activity.AgentOutcome
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.activity.AnchorKind

/**
 * The corner panel's contents, pre-formatted (spec 3.7, issue #159).
 *
 * ## The property this class exists for
 *
 * **Nothing here runs per frame.** A panel showing "world.get_component id=7 - 0.4ms - ok"
 * formats one `String` per row, and formatting six of them sixty times a second is three hundred
 * and sixty short-lived strings a second on the render thread - for a panel whose contents
 * change when the agent calls a tool, which is a few times a *second* at most.
 *
 * So the model holds formatted rows and rebuilds them only when something has actually changed.
 * "Changed" is a pair of monotonic counters - [AgentActivityRing.version] and
 * [AgentNarration.version] - and not a comparison of the rendered text, because comparing the
 * text means formatting it first, which is the work being avoided. The verbosity level is in the
 * gate too: it decides which rows exist.
 *
 * [AgentOverlayView] then draws the rows. The draw path allocates nothing at all.
 *
 * ## Presentation state, wall-timed, never snapshotted
 *
 * Everything read here lives on `AgentBridge`, which is not a component, is in no `FieldStore`
 * and is in no snapshot. A rewind moves the simulation back and leaves this panel alone - which
 * is correct: the agent really did make those calls, and a history that un-made itself as the
 * world rewound would be lying about the only record the human has.
 */
public class AgentOverlayModel(
    private val activity: AgentActivityRing,
    private val narration: AgentNarration,
    private val sessions: AgentSessions,
    /** How many calls the panel lists at [OverlayVerbosity.NORMAL] and above. */
    public val maxCalls: Int = DEFAULT_MAX_CALLS,
) {

    init {
        require(maxCalls > 0) { "a panel lists at least one call, was $maxCalls" }
    }

    /**
     * Row text and row colour, in draw order from the top of the panel.
     *
     * Sized once at construction: header, caption, and [maxCalls] rows. Fixed-size arrays rather
     * than a list because the whole point is that a refresh does not allocate the rows either -
     * only the `String`s that genuinely changed.
     */
    private val rowText = arrayOfNulls<String>(HEADER_ROWS + DEFAULT_MAX_CALLS.coerceAtLeast(maxCalls))

    private val rowColour = IntArray(rowText.size)

    /** Rows currently populated. */
    public var rowCount: Int = 0
        private set

    /** The widest row in characters, so a view can size the panel without measuring twice. */
    public var widestRow: Int = 0
        private set

    private var seenActivityVersion: Long = -1L
    private var seenNarrationVersion: Long = -1L
    private var seenVerbosity: OverlayVerbosity? = null

    /** Panel refreshes since construction. The allocation test's subject. */
    public var refreshes: Long = 0L
        private set

    /** The text of row [index], in draw order from the top. */
    public fun rowText(index: Int): String {
        require(index in 0 until rowCount) { "row $index is outside 0 until $rowCount" }
        return rowText[index] ?: ""
    }

    /** The packed colour of row [index]. */
    public fun rowColour(index: Int): Int {
        require(index in 0 until rowCount) { "row $index is outside 0 until $rowCount" }
        return rowColour[index]
    }

    /**
     * Rebuilds the rows if anything has changed since the last call.
     *
     * @return whether a rebuild happened. Frames on which this returns `false` did no string
     *   work at all, which is the property `OverlayAllocationTest` asserts.
     */
    public fun refreshIfStale(verbosity: OverlayVerbosity): Boolean {
        val activityVersion = activity.version
        val narrationVersion = narration.version
        if (verbosity == seenVerbosity &&
            activityVersion == seenActivityVersion &&
            narrationVersion == seenNarrationVersion
        ) {
            return false
        }
        seenVerbosity = verbosity
        seenActivityVersion = activityVersion
        seenNarrationVersion = narrationVersion
        rebuild(verbosity)
        refreshes++
        return true
    }

    private fun rebuild(verbosity: OverlayVerbosity) {
        rowCount = 0
        widestRow = 0
        if (verbosity == OverlayVerbosity.OFF) return

        val caption = narration.current
        if (caption.isNotEmpty()) {
            add(sessions.label(narration.currentSession) + ": " + caption, OverlayPalette.TEXT)
        } else {
            // A header even with no caption, because an overlay that vanishes when the agent
            // stops narrating is indistinguishable from an overlay that has broken - and a
            // human who cannot tell those apart stops trusting it.
            add(IDLE_HEADER, OverlayPalette.TEXT_DIM)
        }

        if (!verbosity.showsCalls) return

        // `builder` is reused across rows: a refresh allocates one `String` per row that
        // survives, and nothing else.
        activity.forEachRecent(maxCalls) { call ->
            if (rowCount >= rowText.size) return@forEachRecent
            builder.setLength(0)
            builder.append(glyph(call.outcome)).append(' ').append(call.toolName)
            if (call.argDigest.isNotEmpty()) {
                builder.append(' ').append(call.argDigest)
            }
            if (verbosity.showsTimings) {
                builder.append("  #").append(call.commandId)
                builder.append(" t").append(call.tick)
                if (call.outcome != AgentOutcome.RUNNING) {
                    builder.append(' ').append(millis(call.durationNanos)).append("ms")
                }
                if (call.anchorKind == AnchorKind.ENTITY) {
                    builder.append(" @").append(call.anchorNetId)
                } else if (call.anchorKind == AnchorKind.POINT) {
                    builder.append(" @").append(round(call.anchorX))
                        .append(',').append(round(call.anchorY))
                }
            }
            add(builder.toString(), OverlayPalette.forOutcome(call.outcome, call.session))
        }
    }

    private fun add(text: String, colour: Int) {
        if (rowCount >= rowText.size) return
        rowText[rowCount] = text
        rowColour[rowCount] = colour
        rowCount++
        if (text.length > widestRow) widestRow = text.length
    }

    override fun toString(): String = "AgentOverlayModel($rowCount row(s), $refreshes refresh(es))"

    /** Reused by [rebuild]. Never escapes: every row is `toString`d out of it. */
    private val builder = StringBuilder(ROW_BUILDER_CAPACITY)

    private companion object {

        /** The caption row. */
        const val HEADER_ROWS: Int = 1

        /**
         * Six calls.
         *
         * As many as fit a corner panel without covering the game, and enough to show a whole
         * pause / spawn / step / screenshot / rewind sequence, which is the Phase 1 workflow a
         * human is most likely to be watching.
         */
        const val DEFAULT_MAX_CALLS: Int = 6

        /** A tool name, a digest and a timing suffix, without a resize. */
        const val ROW_BUILDER_CAPACITY: Int = 160

        const val IDLE_HEADER: String = "agent: idle"

        /**
         * One character of outcome, in front of every row.
         *
         * A glyph and not a colour, because it survives a colourblind reader, a photograph of a
         * screen and a greyscale screen recording - all three of which are how a human actually
         * reports "the overlay showed this" to somebody else.
         */
        fun glyph(outcome: AgentOutcome): Char = when (outcome) {
            AgentOutcome.RUNNING -> '>'
            AgentOutcome.OK -> '+'
            AgentOutcome.FAILED -> '!'
            AgentOutcome.UNKNOWN -> '?'
        }

        /** Nanoseconds as a one-decimal millisecond figure, without `String.format`'s cost. */
        fun millis(nanos: Long): String {
            val tenths = (nanos + 50_000L) / 100_000L
            return "${tenths / 10}.${tenths % 10}"
        }

        /** A world coordinate to one decimal, for the verbose anchor suffix. */
        fun round(value: Float): String {
            val tenths = Math.round(value * 10f)
            val whole = tenths / 10
            val fraction = if (tenths < 0) -(tenths % 10) else tenths % 10
            return "$whole.$fraction"
        }
    }
}

/**
 * A session's colour, for a legend or a marker.
 *
 * Free function rather than a member because it needs nothing from the model, and a caller with
 * only an id in hand - the marker pass - should not have to hold one.
 */
public fun sessionColour(session: AgentSessionId): Int = OverlayPalette.forSession(session)
