package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.query.FieldRef
import dev.wildware.udea.core.identity.NetId
import kotlin.jvm.JvmInline

/**
 * Which open edit session a call means: what `editor.begin_edit` answers and every later session
 * tool is given back.
 *
 * Numbered from one per editor, in the order sessions were begun, so a replayed editing session
 * hands out the same numbers the recorded one did and the recorded `update_edit` calls still name
 * the session they named.
 */
@JvmInline
internal value class EditSessionId(val raw: Int) {
    override fun toString(): String = "edit #$raw"
}

/**
 * One open edit session: a drag, an inspector field being scrubbed, an agent's live change.
 *
 * It holds what every field held when the session began, so a cancel can put each one back and a
 * commit can record the change as one undo entry whose reverse is that starting state.
 */
internal class EditSession(
    val id: EditSessionId,
    val author: AgentSessionId,
    /** The entities being edited, in the order `begin_edit` named them. */
    val entities: List<NetId>,
    /** The fields being edited, on every one of [entities]. */
    val fields: List<FieldRef>,
    /** Each field's starting value, entity-major: entity `e`'s field `f` is at `e * fields.size + f`. */
    private val start: Array<Any?>,
) {

    /**
     * True when the session was begun or updated since the idle sweep last looked.
     *
     * A flag rather than a time, so a tool call - which runs inside a barrier drain, and so possibly
     * inside `Simulation.step()` - never reads a clock. The sweep, which runs between ticks, turns
     * the flag into [touchedNanos].
     */
    var touched: Boolean = true

    /** When the sweep last found [touched] set, on the editor's idle clock. Meaningless while [touched] is. */
    var touchedNanos: Long = 0L

    /**
     * True once the idle sweep has submitted the cancel that ends this session.
     *
     * So the sweep submits it once, not once per frame until it lands; cleared if the submission
     * was refused, so the next frame tries again.
     */
    var expiring: Boolean = false

    /** The starting value of [field] on the entity at [entity] in [entities]. */
    fun startOf(entity: Int, field: Int): Any? = start[entity * fields.size + field]

    /** The index of [ref] in [fields], or -1 when this session did not open it. */
    fun fieldIndexOf(ref: FieldRef): Int =
        fields.indexOfFirst { it.component === ref.component && it.fieldIndex == ref.fieldIndex }
}

/**
 * The open sessions: at most one per author, indexed by [AgentSessionId.raw].
 *
 * A flat array rather than a map for the reason `EditorHistory` gives: the ids are dense from zero
 * and bounded by the session table's capacity.
 */
internal class EditSessionTable(authors: Int) {

    private val byAuthor = arrayOfNulls<EditSession>(authors)

    private var lastId = 0

    /** The id the next session takes. */
    fun nextId(): EditSessionId = EditSessionId(++lastId)

    /** [author]'s open session, or `null`. */
    fun of(author: AgentSessionId): EditSession? = byAuthor[author.raw]

    /** The open session numbered [id], whoever's it is, or `null`. */
    fun find(id: EditSessionId): EditSession? = byAuthor.firstOrNull { it?.id == id }

    /** Opens [session] as its author's one session. The caller has closed any earlier one. */
    fun open(session: EditSession) {
        check(byAuthor[session.author.raw] == null) { "${session.author} already has ${byAuthor[session.author.raw]?.id} open" }
        byAuthor[session.author.raw] = session
    }

    /** Forgets [session]. */
    fun close(session: EditSession) {
        check(byAuthor[session.author.raw] === session) { "${session.id} is not open" }
        byAuthor[session.author.raw] = null
    }
}

/** How `editor.select` combines the entities it is given with what the author already selected. */
public enum class SelectMode {
    /** The selection becomes exactly these. */
    replace,

    /** These join the selection. */
    add,

    /** These leave the selection. */
    remove,
}

/**
 * One selection per author, which every author can read.
 *
 * Editor state rather than world state: nothing here is snapshotted, hashed or replicated, and a
 * selection naming an entity that has since gone simply stops listing it.
 */
internal class Selections(authors: Int) {

    private val byAuthor = arrayOfNulls<MutableList<NetId>>(authors)

    /** Applies [ids] to [author]'s selection by [mode]. */
    fun select(author: AgentSessionId, ids: List<NetId>, mode: SelectMode) {
        val selection = byAuthor[author.raw] ?: ArrayList<NetId>().also { byAuthor[author.raw] = it }
        when (mode) {
            SelectMode.replace -> {
                selection.clear()
                for (id in ids) if (id !in selection) selection.add(id)
            }
            SelectMode.add -> for (id in ids) if (id !in selection) selection.add(id)
            SelectMode.remove -> selection.removeAll(ids)
        }
    }

    /** Empties [author]'s selection. */
    fun clear(author: AgentSessionId) {
        byAuthor[author.raw]?.clear()
    }

    /** [author]'s selection, in the order it was made. */
    fun of(author: AgentSessionId): List<NetId> = byAuthor[author.raw].orEmpty()

    /** Every author with a selection table, in author order. */
    fun forEachAuthor(action: (AgentSessionId, List<NetId>) -> Unit) {
        for (raw in byAuthor.indices) {
            val selection = byAuthor[raw] ?: continue
            action(AgentSessionId(raw), selection)
        }
    }
}
