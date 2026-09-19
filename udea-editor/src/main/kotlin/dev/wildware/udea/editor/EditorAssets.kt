package dev.wildware.udea.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.udea.agent.AgentResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One value of the open asset, as `assets.fields` lists it.
 *
 * [value] is set for a plain literal the editor may change; [reason] for anything else, and it
 * already names the line (issue #195: a computed field is refused with the reason and the line).
 */
internal class AssetField(
    val name: String,
    /** The line the value is on. */
    val line: Int,
    /** The value as the panel shows and edits it, or `null` when it is read-only. */
    val value: String?,
    /** Why the value is read-only, e.g. "set by `val soldierScale` on line 8"; `null` when it is not. */
    val reason: String?,
) {
    override fun toString(): String = "AssetField($name, line $line)"
}

/**
 * The Asset panel's state and its three actions, Open, Save and Save as new asset (issue #195).
 *
 * ## A screen over the tool surface
 *
 * Like the rest of the window it keeps no model of its own: what it shows is `assets.fields`'
 * answer, and each action is a tool call through [tools] - `assets.set` per changed value,
 * `assets.create` for a copy - so an agent that makes the same calls writes the same bytes. The
 * only thing held here is what the person has typed and not saved yet.
 *
 * ## What it says about the running game
 *
 * The tools hot-reload what they write, when they can. When one could not - a new asset is a new
 * shape, which a running game only takes on its next launch - the message says so in those words,
 * rather than leaving someone to wonder why the viewport did not change.
 */
internal class EditorAssets(private val tools: EditorTools) {

    /** The id in the panel's id field. */
    var id: String by mutableStateOf("")

    /** The new asset's name in the Save as new field. */
    var newName: String by mutableStateOf("")

    /** The open asset's values, as `assets.fields` last listed them. */
    var fields: List<AssetField> by mutableStateOf(emptyList())
        private set

    /** What the last action did, or what to do first. */
    var message: String by mutableStateOf(OPEN_FIRST)
        private set

    /** The id [fields] belong to, once an Open has been answered. */
    private var opened: String? = null

    /** Values typed and not saved, by field name. */
    private val edits = mutableStateMapOf<String, String>()

    /** What [field]'s box shows: what was typed, or the file's value. */
    fun valueOf(field: AssetField): String = edits[field.name] ?: field.value.orEmpty()

    fun edit(field: AssetField, text: String) {
        edits[field.name] = text
    }

    /** Open: lists [id]'s values. Discards anything typed against the asset open before. */
    fun open() {
        val target = id.trim()
        if (target.isEmpty()) {
            message = OPEN_FIRST
            return
        }
        read(target) { message = "Editing $target. Change a value, then Save (Ctrl+S)." }
    }

    /** File > Save and Ctrl+S: every changed value of the open asset, one `assets.set` each. */
    fun save() {
        val target = opened ?: run {
            message = OPEN_FIRST
            return
        }
        val changed = changedValues()
        if (changed.isEmpty()) {
            message = "Nothing to save: no value has changed."
            return
        }
        saveInto(target, changed, prefix = "")
    }

    /**
     * Save as new asset: `assets.create` copies the open asset under [newName], then every changed
     * value is saved into the copy, which the panel then opens.
     */
    fun saveAsNew() {
        val template = opened ?: run {
            message = OPEN_FIRST
            return
        }
        val name = newName.trim()
        if (name.isEmpty()) {
            message = "Type a name for the new asset first."
            return
        }
        val changed = changedValues()
        tools.call(CREATE, mapOf("from" to template, "name" to name)) { answer ->
            val created = when (answer) {
                is AgentResult.Failed -> {
                    message = "Not created: ${answer.error.message}"
                    return@call
                }
                is AgentResult.Ok -> json.parseToJsonElement(answer.json).jsonObject
            }
            val newId = created.string("id")
            if (created["created"]?.jsonPrimitive?.booleanOrNull != true) {
                message = "Not created: $newId did not validate, and the file was removed. ${firstDiagnostic(created)}".trimEnd()
                return@call
            }
            val headline = "Created $newId in ${created.string("path")}. The running game gets new assets on its next launch."
            id = newId
            newName = ""
            message = headline
            if (changed.isEmpty()) {
                read(newId) {}
            } else {
                saveInto(newId, changed, prefix = "$headline ")
            }
        }
    }

    private fun changedValues(): Map<String, String> =
        fields.filter { it.value != null && edits[it.name] != null && edits[it.name] != it.value }
            .associate { it.name to edits.getValue(it.name) }

    /** One `assets.set` per [changed] value into [target]; one message for them all when the last answers. */
    private fun saveInto(target: String, changed: Map<String, String>, prefix: String) {
        val outcomes = arrayOfNulls<String>(changed.size)
        var answered = 0
        changed.entries.forEachIndexed { index, (field, value) ->
            tools.call(SET, mapOf("id" to target, "field" to field, "value" to value)) { answer ->
                outcomes[index] = describe(field, value, answer)
                answered++
                if (answered == changed.size) {
                    message = prefix + outcomes.joinToString(" ")
                    // Re-read, so the boxes show what the file now says rather than what was typed.
                    read(target) {}
                }
            }
        }
    }

    /**
     * `assets.fields` for [target], page after page; when the last page is in, the panel shows
     * [target] and [then] runs. The tool pages so every answer fits what the bridge hands over
     * inline, which is the only kind of answer this panel can read.
     */
    private fun read(target: String, from: Int = 0, before: List<AssetField> = emptyList(), then: () -> Unit) {
        tools.call(FIELDS, if (from == 0) mapOf("id" to target) else mapOf("id" to target, "from" to from.toString())) { answer ->
            when (answer) {
                is AgentResult.Failed -> message = "Cannot open $target: ${answer.error.message}"
                is AgentResult.Ok -> {
                    val page = parsePage(answer.json)
                    val listed = before + page.fields
                    if (page.next != null) {
                        // A page that does not move forward would ask for itself for ever.
                        if (page.next <= from) {
                            message = "Cannot open $target: assets.fields answered page $from with next = ${page.next}"
                        } else {
                            read(target, page.next, listed, then)
                        }
                        return@call
                    }
                    fields = listed
                    opened = target
                    edits.clear()
                    then()
                }
            }
        }
    }

    private fun describe(field: String, value: String, answer: AgentResult): String = when (answer) {
        is AgentResult.Failed -> "Not saved: ${answer.error.message}"
        is AgentResult.Ok -> {
            val result = json.parseToJsonElement(answer.json).jsonObject
            when {
                result.flag("rolledBack") ->
                    "Not saved: `$field` = $value did not validate, and the file is as it was. ${firstDiagnostic(result)}".trimEnd()
                !result.flag("changed") -> "`$field` already had that value."
                result.flag("applied") -> "Saved `$field` = $value into ${result.string("path")}. The running game has it now."
                else -> "Saved `$field` = $value into ${result.string("path")}. It applies on the next launch."
            }
        }
    }

    private fun firstDiagnostic(result: JsonObject): String =
        result["diagnostics"]?.jsonArray?.firstOrNull()?.jsonObject?.string("message").orEmpty()

    override fun toString(): String = "EditorAssets(opened=$opened, ${edits.size} unsaved)"

    private companion object {
        const val FIELDS = "assets.fields"
        const val SET = "assets.set"
        const val CREATE = "assets.create"
        const val OPEN_FIRST = "Type an asset id, such as character/soldier_idle_sheet, and Open it."

        val json = Json { ignoreUnknownKeys = true }

        fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.content.orEmpty()

        fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true

        /** One `assets.fields` answer: its fields, and where the next page starts if there is one. */
        class Page(val fields: List<AssetField>, val next: Int?)

        /**
         * One page of `assets.fields`' answer.
         *
         * @throws IllegalArgumentException when it is not that shape. Loud, for `HistoryEntry.parse`'s
         *   reason: a panel that showed no fields after the tool's answer changed shape would read as
         *   an asset with nothing to edit.
         */
        fun parsePage(answer: String): Page {
            val root = json.parseToJsonElement(answer) as? JsonObject
                ?: throw IllegalArgumentException("assets.fields answered something that is not an object: $answer")
            val fields = root["fields"] ?: throw IllegalArgumentException("assets.fields answered with no fields member: $answer")
            return Page(
                fields = fields.jsonArray.map { element ->
                    val field = element.jsonObject
                    val editable = field.getValue("editable").jsonPrimitive.boolean
                    AssetField(
                        name = field.getValue("name").jsonPrimitive.content,
                        line = field.getValue("line").jsonPrimitive.int,
                        value = if (editable) field.getValue("value").jsonPrimitive.content else null,
                        reason = if (editable) null else field.getValue("reason").jsonPrimitive.content,
                    )
                },
                next = root["next"]?.jsonPrimitive?.int,
            )
        }
    }
}
