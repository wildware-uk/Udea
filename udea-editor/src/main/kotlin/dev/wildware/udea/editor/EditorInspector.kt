package dev.wildware.udea.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One field every selected entity has, as `editor.common_fields` lists it.
 *
 * @property value the shared value as the panel shows and edits it; `null` when the entities
 *   disagree ([mixed]) or when the value is not one line of text ([readOnly]).
 * @property readOnly true for a value that is a structure rather than a number, word or flag: shown,
 *   never typed into.
 */
internal class InspectorField(
    val component: String,
    val field: String,
    val mixed: Boolean,
    val value: String?,
    val readOnly: Boolean,
) {
    /** How the panel names it, and what its edits are keyed by: `Component.field`. */
    val key: String = "$component.$field"

    override fun toString(): String = "InspectorField($key${if (mixed) ", mixed" else ""})"
}

/**
 * The Inspector panel's state (issue #235): the fields every selected entity shares, and typing into
 * one writes it to all of them.
 *
 * ## A screen over the tool surface
 *
 * What it lists is `editor.common_fields`' answer for the editor's selection ([EditorSelection]): a
 * field the entities agree on shows its value, and one they do not shows **Mixed**.
 *
 * Typing is an edit session, G1's: the first keystroke in a box opens `editor.begin_edit` over the
 * selection and that one field, each keystroke is an `editor.update_edit` the tool writes to every
 * entity live and files nothing for, and Enter or leaving the box is `editor.commit_edit` - **one**
 * undo entry, from where every entity started, however many keystrokes it took. There is no Set
 * button (the owner's ruling on #235). A value the field cannot hold is refused by the tool with
 * nothing written, and a box left holding one is `editor.cancel_edit`, which puts every entity back.
 * An agent making the same calls gets the same edit.
 *
 * ## When it reads
 *
 * Again whenever the selection changes, whenever anything but the editor's own reads has completed
 * (an edit, the editor's or an agent's), and every [RUNNING_READ_TICKS] ticks while the world runs,
 * since a running world changes values with no tool call at all. One read at a time.
 *
 * Render thread only, like [EditorTools].
 */
internal class EditorInspector(
    private val tools: EditorTools,
    private val selection: EditorSelection,
) {

    /** The shared fields, as `editor.common_fields` last listed them for the selection. */
    var fields: List<InspectorField> by mutableStateOf(emptyList())
        private set

    /** How many entities [fields] are shared by. */
    var count: Int by mutableStateOf(0)
        private set

    /** What the last edit did, or why it was not kept. */
    var message: String by mutableStateOf("")
        private set

    /** What is in each box that differs from the value read, by [InspectorField.key]. */
    private val edits = mutableStateMapOf<String, String>()

    /** The field being typed into, and its edit session: one at a time. */
    private var typing: Typing? = null

    /** The selection [fields] were read for, and the tick they were read at. */
    private var readFor: List<NetId> = emptyList()
    private var readAt: Tick? = null

    private var stale = true
    private var pending = false

    /** What [field]'s box shows: what was typed, or the shared value, or nothing when it is mixed. */
    fun textOf(field: InspectorField): String = edits[field.key] ?: field.value.orEmpty()

    /**
     * [text] is now in [field]'s box, and is written to every selected entity as it is typed: the
     * first keystroke opens the edit session, each one after it sends the whole box. Typing into
     * another box first ends the one before, as leaving it would.
     */
    fun edit(field: InspectorField, text: String) {
        edits[field.key] = text
        val open = typing
        if (open != null && open.key != field.key) finish(open)
        val current = typing ?: begin(field) ?: return
        current.latest = text
        flush(current)
    }

    /**
     * Ends the typing in [field] - Enter, or the box losing focus - as one undo entry, or puts every
     * entity back when the box holds a value the field cannot. Nothing when [field] is not being typed in.
     */
    fun commit(field: InspectorField) {
        val open = typing ?: return
        if (open.key == field.key) finish(open)
    }

    /** Opens the edit session for [field] over the selection read, or null when nothing is selected. */
    private fun begin(field: InspectorField): Typing? {
        val entities = readFor
        if (entities.isEmpty()) return null
        val opened = Typing(field.key, entities.size)
        typing = opened
        opened.inFlight = true
        val args = mapOf("entities" to entities.joinToString(",") { it.raw.toString() }, "fields" to field.key)
        tools.call(BEGIN_EDIT, args) { answer ->
            opened.inFlight = false
            when (answer) {
                is AgentResult.Ok -> {
                    opened.session = sessionIdOf(answer.json)
                    flush(opened)
                }
                is AgentResult.Failed -> {
                    message = "Not editable: ${answer.error.message}"
                    if (typing === opened) typing = null
                    edits.remove(opened.key)
                }
            }
        }
        return opened
    }

    /**
     * Sends [open]'s newest text when it has not been sent, one call at a time so they arrive in
     * order, and closes the session once it has been asked to and everything typed has been sent.
     */
    private fun flush(open: Typing) {
        val session = open.session ?: return
        if (open.inFlight) return
        val text = open.latest
        if (text != null && text != open.sent) {
            open.inFlight = true
            open.sent = text
            tools.call(UPDATE_EDIT, mapOf("sessionId" to session.toString(), "values" to "${open.key}=$text")) { answer ->
                open.inFlight = false
                open.valid = answer is AgentResult.Ok
                message = when (answer) {
                    is AgentResult.Ok -> ""
                    is AgentResult.Failed -> "Not written: ${answer.error.message}"
                }
                flush(open)
            }
            return
        }
        if (open.finishing) close(open, session)
    }

    private fun finish(open: Typing) {
        if (typing === open) typing = null
        open.finishing = true
        flush(open)
    }

    /** `editor.commit_edit` when the last value sent was written, `editor.cancel_edit` when it was refused. */
    private fun close(open: Typing, session: Int) {
        val keep = open.valid
        tools.call(if (keep) COMMIT_EDIT else CANCEL_EDIT, mapOf("sessionId" to session.toString())) { answer ->
            // Unless the same box is being typed in again already.
            if (typing?.key != open.key) edits.remove(open.key)
            message = when {
                answer is AgentResult.Failed -> "Not kept: ${answer.error.message}"
                keep -> "Set ${open.key} = ${open.sent} on ${open.entities} ${if (open.entities == 1) "entity" else "entities"}: one undo."
                else -> "${open.key} cannot hold \"${open.sent}\", so every entity was put back."
            }
        }
    }

    /**
     * Reads the shared fields again if they may be out of date. Once a frame, after the tools have
     * delivered their answers.
     *
     * @param changed whether anything but the editor's own reads has completed since the last frame.
     * @param tick the simulation's tick now.
     */
    fun frame(changed: Boolean, tick: Tick) {
        val selected = selection.ids
        if (changed || selected != readFor || (selected.isNotEmpty() && ticked(tick))) stale = true
        if (!stale || pending) return
        stale = false
        read(selected, tick)
    }

    private fun ticked(tick: Tick): Boolean {
        val at = readAt ?: return true
        return tick.ticksSince(at) >= RUNNING_READ_TICKS || tick < at
    }

    private fun read(selected: List<NetId>, tick: Tick) {
        if (selected.isEmpty()) {
            show(selected, tick, emptyList())
            return
        }
        pending = true
        tools.read(COMMON_FIELDS, mapOf("entities" to selected.joinToString(",") { it.raw.toString() })) { answer ->
            pending = false
            when (answer) {
                is AgentResult.Ok -> show(selected, tick, parse(answer.json))
                // An entity was removed since it was selected: `editor.selection` no longer lists it,
                // and the read that follows is for what is left.
                is AgentResult.Failed -> {
                    show(selected, tick, emptyList())
                    message = "$COMMON_FIELDS refused: ${answer.error.message}"
                }
            }
        }
    }

    private fun show(selected: List<NetId>, tick: Tick, listed: List<InspectorField>) {
        if (selected != readFor) {
            // The typing was about a selection that has gone: what it wrote is kept, as one edit.
            typing?.let(::finish)
            edits.clear()
        }
        readFor = selected
        readAt = tick
        count = selected.size
        fields = listed
    }

    override fun toString(): String = "EditorInspector(${fields.size} fields over $count)"

    internal companion object {
        const val COMMON_FIELDS = "editor.common_fields"
        const val BEGIN_EDIT = "editor.begin_edit"
        const val UPDATE_EDIT = "editor.update_edit"
        const val COMMIT_EDIT = "editor.commit_edit"
        const val CANCEL_EDIT = "editor.cancel_edit"

        /**
         * How often the values are read again while the world runs, in ticks: four times a second at
         * 60Hz. Every tick would be sixty tool calls a second through the bridge an agent watches,
         * for a panel a person reads at a glance.
         */
        const val RUNNING_READ_TICKS: Long = 15L

        private val json = Json { ignoreUnknownKeys = true }

        /** `editor.begin_edit`'s session id, loudly: without it nothing typed could be written. */
        fun sessionIdOf(answer: String): Int {
            val root = json.parseToJsonElement(answer) as? JsonObject
                ?: throw IllegalArgumentException("$BEGIN_EDIT answered something that is not an object: $answer")
            return root["sessionId"]?.jsonPrimitive?.int
                ?: throw IllegalArgumentException("$BEGIN_EDIT answered with no sessionId: $answer")
        }

        /**
         * `editor.common_fields`' answer as panel rows.
         *
         * @throws IllegalArgumentException when it is not that shape. Loud, for
         *   `HistoryEntry.parse`'s reason: an inspector that showed no fields after the tool's answer
         *   changed shape would read as entities with nothing in common.
         */
        fun parse(answer: String): List<InspectorField> {
            val root = json.parseToJsonElement(answer) as? JsonObject
                ?: throw IllegalArgumentException("$COMMON_FIELDS answered something that is not an object: $answer")
            val fields = root["fields"] ?: throw IllegalArgumentException("$COMMON_FIELDS answered with no fields member: $answer")
            return fields.jsonArray.map { element ->
                val field = element.jsonObject
                val mixed = field.getValue("mixed").jsonPrimitive.boolean
                val value = field["value"]
                InspectorField(
                    component = field.getValue("component").jsonPrimitive.content,
                    field = field.getValue("field").jsonPrimitive.content,
                    mixed = mixed,
                    value = when (value) {
                        null -> null
                        is JsonPrimitive -> value.content
                        else -> value.toString()
                    },
                    readOnly = value != null && value !is JsonPrimitive,
                )
            }
        }
    }
}

/**
 * Typing into one field, as one `editor.begin_edit` session: what the box holds, what has been sent
 * of it, and whether the tool took the last value sent.
 */
private class Typing(val key: String, val entities: Int) {
    /** The session id `editor.begin_edit` answered; null until it has. */
    var session: Int? = null

    /** The box's newest text. */
    var latest: String? = null

    /** The last text sent in an `editor.update_edit`. */
    var sent: String? = null

    /** Whether the tool wrote the last value sent. Only a written value is committed. */
    var valid = false

    /** A call for this session is out: the next waits for its answer, so they arrive in order. */
    var inFlight = false

    /** Enter was pressed or the box was left: close once everything typed has been sent. */
    var finishing = false

    override fun toString(): String = "Typing($key, session=$session, sent=$sent)"
}
