package dev.wildware.udea.editor

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentSubmission
import dev.wildware.udea.agent.activity.AgentSessionId

/**
 * The editor's hands: every action it takes is a tool call on the same [AgentBridge] an agent's HTTP
 * call reaches, filed under one [author].
 *
 * ## Why in-process calls go through the bridge
 *
 * Because that is what "the editor is a screen over the tool surface" means (issue #190). A button
 * that called `EditorToolset.spawn` directly would skip the queue, the barrier and the dispatcher,
 * and would be a second path to the world that nothing an agent does exercises. Through the bridge,
 * a click is an `AgentCommand` like any other: it is drained at the top of a frame, applied between
 * ticks by the `SimBarrier`, audited, answered - and recorded in [author]'s undo history, because the
 * author of an `editor.*` call is the session its command carries.
 *
 * ## The author
 *
 * An [AgentSessionId] interned from a label by the host's `AgentSessions`, so an agent that sends
 * `session=<that label>` over HTTP is the *same* author: it sees the editor's edits in
 * `editor.history` and can undo them, which is the one-history-per-author rule of issue #193.
 *
 * Render thread only: [call] is made from a click handler and [frame] from the frame callback, and
 * both touch [waiting] without a lock.
 */
public class EditorTools(
    private val bridge: AgentBridge,
    /** Who the editor's calls are filed under. */
    private val author: AgentSessionId,
) {

    /** Answers still owed, by command id, oldest first. */
    private val waiting = LinkedHashMap<Long, (AgentResult) -> Unit>()

    /** The highest command id this editor itself has sent. */
    internal var lastSent: Long = 0L
        private set

    /** The highest command id the bridge had completed at the last [frame]. */
    internal val completed: Long get() = bridge.completedCommandId()

    /**
     * Sends [tool] with [args] as [author], and hands its answer to [onAnswer] at the [frame] after
     * the simulation ran it. A command the bridge refuses - its queue is full - is answered at once
     * with that refusal, so a caller never waits on a command that does not exist.
     *
     * @return the command's id, which is what [AgentBridge.completedCommandId] reaches when it has run.
     */
    internal fun call(tool: String, args: Map<String, String> = emptyMap(), onAnswer: (AgentResult) -> Unit): Long {
        val command = AgentCommand(tool, args, session = author)
        lastSent = command.id
        if (firstSent == 0L) firstSent = command.id
        when (val submitted = bridge.submit(command)) {
            is AgentSubmission.Accepted -> waiting[submitted.commandId] = onAnswer
            is AgentSubmission.Rejected -> onAnswer(AgentResult.Failed(submitted.error))
        }
        return command.id
    }

    /** The ids of this editor's own reads ([read]) not yet passed by [consumeChanged]. */
    private val reads = HashSet<Long>()

    /** The highest completed command id [consumeChanged] has looked past. */
    private var changesSeen: Long = 0L

    /** The id of the first command this editor sent, or 0 before it has sent any. */
    private var firstSent: Long = 0L

    /**
     * [call] for a tool that only reads - `editor.history`, `editor.selection`,
     * `editor.common_fields` - so that its completing is not taken for a change ([consumeChanged]).
     * Without that, two panels that each re-read on every completed command would each wake the
     * other for ever.
     */
    internal fun read(tool: String, args: Map<String, String> = emptyMap(), onAnswer: (AgentResult) -> Unit): Long =
        call(tool, args, onAnswer).also { reads += it }

    /**
     * True when a command other than this editor's own [read]s has completed since the last call:
     * an edit, the editor's or an agent's, or anything else that may have changed what the panels
     * show. Command ids are one counter for every caller, so each id passed that is not one of this
     * editor's reads is somebody's command.
     *
     * Nothing before this editor's first call counts: a command with a lower id ran before that call
     * did, so the first read of every panel already saw what it changed.
     */
    internal fun consumeChanged(): Boolean {
        val now = bridge.completedCommandId()
        if (firstSent == 0L) {
            changesSeen = now
            return false
        }
        var changed = false
        var id = maxOf(changesSeen, firstSent - 1) + 1
        while (id <= now) {
            if (id !in reads) {
                changed = true
                break
            }
            id++
        }
        reads.removeAll { it <= now }
        changesSeen = now
        return changed
    }

    /**
     * Delivers every answer that has arrived since the last call. Once per frame, on the render thread.
     *
     * An answer is read out of the bridge's ring of recent results. One the ring has already let go
     * of - a burst of other callers can push an old answer out before this frame reads it - is
     * delivered as [ANSWER_LOST] rather than left waiting for ever: every answer in the ring is newer
     * than it, so it ran, and saying so is better than a button that never comes back.
     */
    internal fun frame() {
        if (waiting.isEmpty()) return
        if (waiting.keys.none { it <= bridge.completedCommandId() }) return
        val results = bridge.commandResults()
        val answers = results.associateBy { it.id }
        val oldestKept = results.minOfOrNull { it.id } ?: return
        for (id in waiting.keys.toList()) {
            val answer = answers[id]?.result
                ?: if (id < oldestKept) AgentResult.failed(ANSWER_LOST, "command $id ran, but its answer left the result ring before the editor read it") else continue
            waiting.remove(id)?.invoke(answer)
        }
    }

    override fun toString(): String = "EditorTools(author=$author, waiting=${waiting.size})"

    internal companion object {

        /** A command that completed with an answer nobody can read any more. */
        val ANSWER_LOST: AgentErrorKind = AgentErrorKind("answer_lost")
    }
}
