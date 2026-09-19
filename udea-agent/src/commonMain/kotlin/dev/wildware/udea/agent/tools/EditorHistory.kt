package dev.wildware.udea.agent.tools

import com.github.quillraven.fleks.Snapshot
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.core.identity.NetId

/**
 * One undoable edit made through an `editor.*` tool.
 *
 * [sequence] orders edits across **every** author, which is what lets a refused undo name the
 * author whose later edit is in the way: each author's history is ordered on its own, and two
 * histories can only be compared through a counter they share.
 */
internal sealed class EditorEdit(
    /** Position in the order every author's edits were made in. */
    val sequence: Long,
    /** Who made it: the session the command carried. */
    val author: AgentSessionId,
    /** The tool that made it, as an agent would call it. */
    val tool: String,
) {

    /** The entity it was made to; the first one, for an edit made to several. */
    abstract val netId: NetId

    /** Whether this edit wrote field [fieldIndex] of [component] on [id]. */
    open fun touches(id: NetId, component: AgentComponentType, fieldIndex: Int): Boolean = false

    /** Whether this edit was made to [id] at all. */
    open fun touchesEntity(id: NetId): Boolean = id == netId

    /**
     * `editor.set_field`, `editor.move` and a committed edit session: fields written, on one entity
     * or several, with what each held either side. One edit however many entities it names, so
     * one undo puts every one of them back.
     */
    class Fields(
        sequence: Long,
        author: AgentSessionId,
        tool: String,
        val changes: List<FieldChange>,
    ) : EditorEdit(sequence, author, tool) {

        init {
            require(changes.isNotEmpty()) { "$tool recorded an edit that wrote nothing" }
        }

        override val netId: NetId get() = changes[0].netId

        /** Every entity this edit wrote to, in the order it wrote them. */
        val netIds: List<NetId> = changes.map { it.netId }.distinct()

        override fun touches(id: NetId, component: AgentComponentType, fieldIndex: Int): Boolean =
            changes.any { it.netId == id && it.component === component && it.fieldIndex == fieldIndex }

        override fun touchesEntity(id: NetId): Boolean = changes.any { it.netId == id }
    }

    /** `editor.spawn`: undone by removing the entity and giving its id back. */
    class Spawn(sequence: Long, author: AgentSessionId, override val netId: NetId) :
        EditorEdit(sequence, author, "editor.spawn")

    /**
     * `editor.delete`: the removed entity's components, held so an undo can put them back.
     *
     * Fleks' own per-entity snapshot - the component instances themselves, not an encoding of
     * them - so it is not a second codec: nothing is written, read or diffed, and an undo hands
     * back the very objects the delete took away. The id stays an outstanding reservation in the
     * `NetIdIndex` for as long as this edit is in a history; see `NetIdIndex.detach`.
     */
    class Delete(sequence: Long, author: AgentSessionId, override val netId: NetId, val removed: Snapshot) :
        EditorEdit(sequence, author, "editor.delete")
}

/** One field an edit wrote: on which entity, which field, what it held before, and what the edit left in it. */
internal class FieldChange(
    val netId: NetId,
    val component: AgentComponentType,
    val fieldIndex: Int,
    val before: Any?,
    val after: Any?,
) {
    val fieldName: String get() = component.fieldNames[fieldIndex]
}

/**
 * The undo histories: one per author, each capped, oldest dropped first.
 *
 * Indexed by [AgentSessionId.raw], which is dense from zero and bounded by the session table's
 * capacity, so the histories are a flat array rather than a hash-ordered map on the simulation
 * thread. Saving a level does not touch this: nothing here is cleared except by an undo.
 */
internal class EditorHistory(
    /** How many authors can hold a history: the session table's capacity. */
    authors: Int,
    /** Edits kept per author. */
    private val capacity: Int,
    /** Told about every edit that falls off the old end, so it can release what the edit held. */
    private val onDrop: (EditorEdit) -> Unit,
) {

    private val stacks = arrayOfNulls<ArrayDeque<EditorEdit>>(authors)

    private var lastSequence = 0L

    /** The sequence number the next recorded edit takes. */
    fun nextSequence(): Long = ++lastSequence

    /** Records [edit] as its author's newest, dropping that author's oldest past [capacity]. */
    fun push(edit: EditorEdit) {
        val stack = stackOf(edit.author) ?: ArrayDeque<EditorEdit>().also { stacks[edit.author.raw] = it }
        stack.addLast(edit)
        if (stack.size > capacity) onDrop(stack.removeFirst())
    }

    /** Every author's history as it stands, for [restore] to put back. What `editor.play` keeps. */
    fun mark(): HistoryMark = HistoryMark(Array(stacks.size) { author -> stacks[author]?.toList() }, lastSequence)

    /**
     * Puts every author's history back to [mark], and answers the edits that were in a history now
     * and are not in the mark - the ones made since, newest last.
     *
     * An edit undone since the mark would come back with it, because the world it is restored
     * alongside (`editor.stop`'s) is one in which that edit still stands; `editor.undo` refuses an
     * edit that predates a play while the play is under way, so Stop never meets one. [onDrop] is not told about the
     * answered edits: what they held belongs to a world that no longer exists.
     */
    fun restore(mark: HistoryMark): List<EditorEdit> {
        val since = ArrayList<EditorEdit>()
        for (author in stacks.indices) {
            val kept = mark.stacks[author]
            val newestKept = kept?.lastOrNull()?.sequence ?: Long.MIN_VALUE
            stacks[author]?.let { stack -> stack.filterTo(since) { it.sequence > newestKept } }
            stacks[author] = kept?.let(::ArrayDeque)
        }
        since.sortBy(EditorEdit::sequence)
        return since
    }

    /** [author]'s newest edit, or `null` when there is nothing to undo. */
    fun newest(author: AgentSessionId): EditorEdit? = stackOf(author)?.lastOrNull()

    /** Removes [author]'s newest edit, which must be the one the caller just undid or discarded. */
    fun pop(author: AgentSessionId): EditorEdit = checkNotNull(stackOf(author)).removeLast()

    /** How many edits [author] can undo. */
    fun size(author: AgentSessionId): Int = stackOf(author)?.size ?: 0

    /** [author]'s edits, newest first, at most [limit] of them. */
    fun newestFirst(author: AgentSessionId, limit: Int): List<EditorEdit> =
        stackOf(author)?.asReversed()?.take(limit).orEmpty()

    /**
     * The latest edit by anyone but [edit]'s author, made after it, that [matches] accepts.
     *
     * What a refused undo names. Walks every history, which is at most the session capacity times
     * [capacity] entries, once per refusal and never per tick.
     */
    fun latestByOthers(edit: EditorEdit, matches: (EditorEdit) -> Boolean): EditorEdit? {
        var latest: EditorEdit? = null
        for (stack in stacks) {
            if (stack == null) continue
            for (candidate in stack) {
                if (candidate.author == edit.author || candidate.sequence <= edit.sequence) continue
                if (!matches(candidate)) continue
                if (latest == null || candidate.sequence > latest.sequence) latest = candidate
            }
        }
        return latest
    }

    private fun stackOf(author: AgentSessionId): ArrayDeque<EditorEdit>? {
        check(author.raw < stacks.size) {
            "$author is outside the editor's session table of ${stacks.size}; the toolset and the " +
                "host must share one AgentSessions"
        }
        return stacks[author.raw]
    }
}

/** Every author's history at one moment, from [EditorHistory.mark]. The lists are copies; the edits are shared. */
internal class HistoryMark(
    val stacks: Array<List<EditorEdit>?>,
    /** The last sequence number handed out when the mark was taken: every edit at or below it predates the mark. */
    val lastSequence: Long,
) {

    /** Whether [edit] was made before the mark was taken. */
    fun predates(edit: EditorEdit): Boolean = edit.sequence <= lastSequence

    /** Every `editor.delete` in the marked histories, whoever made it. */
    fun deletes(): List<EditorEdit.Delete> = stacks.flatMap { it.orEmpty() }.filterIsInstance<EditorEdit.Delete>()
}
