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
 * The Inspector panel's state (issue #235): the fields every selected entity shares, and one write
 * that changes them all.
 *
 * ## A screen over the tool surface
 *
 * What it lists is `editor.common_fields`' answer for the editor's selection ([EditorSelection]): a
 * field the entities agree on shows its value, and one they do not shows **Mixed**. Setting a field
 * is one `editor.set_field` naming every selected entity, which the tool applies as one write and
 * files as **one** undo entry - so an agent making the same call gets the same edit, and Undo puts
 * every entity back at once. The only thing held here is what the person has typed and not set.
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

    /** What the last set did. */
    var message: String by mutableStateOf("")
        private set

    /** Values typed and not set, by [InspectorField.key]. */
    private val edits = mutableStateMapOf<String, String>()

    /** The selection [fields] were read for, and the tick they were read at. */
    private var readFor: List<NetId> = emptyList()
    private var readAt: Tick? = null

    private var stale = true
    private var pending = false

    /** What [field]'s box shows: what was typed, or the shared value, or nothing when it is mixed. */
    fun textOf(field: InspectorField): String = edits[field.key] ?: field.value.orEmpty()

    fun edit(field: InspectorField, text: String) {
        edits[field.key] = text
    }

    /**
     * Sets [field] on every selected entity to what was typed into it, as one `editor.set_field`:
     * one write, one undo entry. Nothing is sent when nothing was typed.
     */
    fun set(field: InspectorField) {
        val text = edits[field.key] ?: return
        val entities = readFor
        if (entities.isEmpty()) return
        val args = mapOf(
            "id" to entities.joinToString(",") { it.raw.toString() },
            "component" to field.component,
            "field" to field.field,
            "value" to text,
        )
        tools.call(SET_FIELD, args) { answer ->
            message = when (answer) {
                is AgentResult.Ok -> {
                    edits.remove(field.key)
                    "Set ${field.key} = $text on ${entities.size} ${if (entities.size == 1) "entity" else "entities"}: one undo."
                }
                is AgentResult.Failed -> "Not set: ${answer.error.message}"
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
        if (selected != readFor) edits.clear()
        readFor = selected
        readAt = tick
        count = selected.size
        fields = listed
    }

    override fun toString(): String = "EditorInspector(${fields.size} fields over $count)"

    internal companion object {
        const val COMMON_FIELDS = "editor.common_fields"
        const val SET_FIELD = "editor.set_field"

        /**
         * How often the values are read again while the world runs, in ticks: four times a second at
         * 60Hz. Every tick would be sixty tool calls a second through the bridge an agent watches,
         * for a panel a person reads at a glance.
         */
        const val RUNNING_READ_TICKS: Long = 15L

        private val json = Json { ignoreUnknownKeys = true }

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
