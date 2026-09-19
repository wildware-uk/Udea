package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.core.Tick

/**
 * One `editor.*` call that changed something, as it was made: when, by whom, and with what.
 *
 * The call itself rather than what it wrote, because the call is what a replay can make again:
 * `begin_edit` hands out a session id and `spawn` a `NetId`, and both come out the same when the
 * same calls are made against the same world in the same order. Recording the writes instead
 * would be a second description of an edit beside the tool, which is the thing that drifts.
 */
public class EditorJournalEntry(
    /** The tick whose barrier drain applied the call: before any system ran on that tick. */
    public val tick: Tick,
    /** Who made it, as the session table labels them - what `?session=` carried. */
    public val author: String,
    /** The tool, as an agent calls it. */
    public val tool: String,
    /** Its arguments, verbatim. */
    public val args: Map<String, String>,
) {

    /**
     * The same call again, from the same author, for a replay to submit.
     *
     * [sessions] is the replaying host's table: the author is interned there by label, so a
     * replay run in a fresh process names the same author the recording did.
     */
    public fun command(sessions: AgentSessions): AgentCommand =
        AgentCommand(tool, args, session = sessions.intern(author))

    override fun toString(): String = "$tick $author $tool $args"
}

/**
 * Every `editor.*` call that changed the world or an edit session, in the order they were applied.
 *
 * ## Why this exists
 *
 * A `.udearep` records each tick's *input* and the world hash the tick produced, and a replay
 * reproduces a match by feeding the input back. An edit is not input - it arrives between ticks
 * through the `SimBarrier`, from an agent or the editor window - so a replay of the input alone
 * cannot reproduce a world somebody edited. This is the other half: each call with the tick it was
 * applied on, which a replay submits again through the same bridge, onto the same barrier, before
 * the same tick. An idle session's cancel is here too, as the `editor.cancel_edit` it was, so a
 * replay does not need the wall clock that decided when it happened.
 *
 * Only calls that succeeded are kept: a refused call changed nothing, and replaying it would
 * change nothing either. Reads (`history`, `selection`, `common_fields`), `save` and `select` are
 * not kept, because none of them changes the world.
 *
 * ## Bounded
 *
 * A drag updates every frame, so an hour of editing is a lot of calls. Past [capacity] the journal
 * stops and [complete] turns false rather than dropping its oldest entries: a journal missing its
 * start replays into a different world with no sign of why, and a replay can refuse an incomplete
 * one instead.
 */
public class EditorJournal internal constructor(
    /** How many calls are kept before recording stops. */
    public val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(capacity > 0) { "an editor journal holds at least one call, was $capacity" }
    }

    private val kept = ArrayList<EditorJournalEntry>()

    /** Every recorded call, oldest first. */
    public val entries: List<EditorJournalEntry> get() = kept

    /** False once a call arrived with the journal full, so it was not recorded. */
    public var complete: Boolean = true
        private set

    internal fun record(entry: EditorJournalEntry) {
        if (kept.size >= capacity) {
            complete = false
            return
        }
        kept.add(entry)
    }

    override fun toString(): String =
        "EditorJournal(${kept.size}/$capacity${if (complete) "" else ", incomplete"})"

    internal companion object {
        /** A hundred thousand calls: about half an hour of a drag updating every frame at 60Hz. */
        const val DEFAULT_CAPACITY: Int = 100_000
    }
}
