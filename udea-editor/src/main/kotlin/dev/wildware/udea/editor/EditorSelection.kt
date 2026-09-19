package dev.wildware.udea.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.identity.NetId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The editor author's selection (issue #235), as the tool surface holds it.
 *
 * ## A screen over the tool surface
 *
 * The selection lives in `editor.select`'s table, one per author, and not here. Every change a
 * gesture makes is an `editor.select` call filed under the editor's author, so an agent reading
 * `editor.selection` sees what a person clicked, and an agent calling `editor.select` with
 * `session=editor` changes what the window shows. [ids] is only the window's copy of the answer: set
 * from each `editor.select` answer, and read again with `editor.selection` whenever something else
 * may have changed it ([frame]).
 *
 * Render thread only, like [EditorTools].
 */
internal class EditorSelection(
    private val tools: EditorTools,
    /** Told when a call is refused, so the status line can say so. */
    private val refused: (String) -> Unit,
) {

    /** The selected entities, as the tools last answered, in the order they hold them. */
    var ids: List<NetId> by mutableStateOf(emptyList())
        private set

    /** True from any change to [ids] until [consumeChanged] reads it: the Scene tab must draw again. */
    private var changed = false

    /** Whether [ids] may be out of date, and whether a read of it is on its way. */
    private var stale = true
    private var pending = false

    /** Replaces the selection with [entities]; none clears it. */
    fun replace(entities: List<NetId>) {
        select(entities, REPLACE)
    }

    /** Adds [entities] to the selection. */
    fun add(entities: List<NetId>) {
        if (entities.isNotEmpty()) select(entities, ADD)
    }

    /** Takes [entities] out of the selection. */
    fun remove(entities: List<NetId>) {
        if (entities.isNotEmpty()) select(entities, REMOVE)
    }

    /**
     * Reads the editor author's selection back from `editor.selection` if it may be out of date:
     * on the first frame, and whenever anything but the editor's own reads has completed ([changed])
     * - an agent's `editor.select` under the same author, or a deleted entity, changes it. One read at
     * a time. Once a frame, after the tools have delivered their answers.
     */
    fun frame(changed: Boolean) {
        if (changed) stale = true
        if (!stale || pending) return
        stale = false
        pending = true
        tools.read(SELECTION) { answer ->
            pending = false
            when (answer) {
                is AgentResult.Ok -> show(ownSelection(answer.json))
                is AgentResult.Failed -> refused("$SELECTION refused: ${answer.error}")
            }
        }
    }

    /** Whether [ids] has changed since the last call. */
    fun consumeChanged(): Boolean = changed.also { changed = false }

    private fun select(entities: List<NetId>, mode: String) {
        val args = if (entities.isEmpty()) {
            mapOf("mode" to mode)
        } else {
            mapOf("entities" to entities.joinToString(",") { it.raw.toString() }, "mode" to mode)
        }
        tools.call(SELECT, args) { answer ->
            when (answer) {
                is AgentResult.Ok -> show(idsOf(json.parseToJsonElement(answer.json).jsonObject))
                is AgentResult.Failed -> refused("$SELECT refused: ${answer.error}")
            }
        }
    }

    private fun show(selected: List<NetId>) {
        if (selected == ids) return
        ids = selected
        changed = true
    }

    override fun toString(): String = "EditorSelection(${ids.size} selected)"

    internal companion object {
        const val SELECT = "editor.select"
        const val SELECTION = "editor.selection"

        private const val REPLACE = "replace"
        private const val ADD = "add"
        private const val REMOVE = "remove"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The calling author's own selection out of `editor.selection`'s answer: the entry whose
         * author is the one the answer names as `you`, or nothing when that author has none.
         *
         * @throws IllegalArgumentException when the answer is not that shape. Loud, for
         *   `HistoryEntry.parse`'s reason: a window that showed nothing selected after the tool's
         *   answer changed shape would read as a selection that had been cleared.
         */
        fun ownSelection(answer: String): List<NetId> {
            val root = json.parseToJsonElement(answer) as? JsonObject
                ?: throw IllegalArgumentException("$SELECTION answered something that is not an object: $answer")
            val you = root["you"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("$SELECTION answered with no `you` member: $answer")
            val authors = root["authors"]?.jsonArray
                ?: throw IllegalArgumentException("$SELECTION answered with no `authors` member: $answer")
            val own = authors.map { it.jsonObject }.firstOrNull { it["author"]?.jsonPrimitive?.content == you }
            return own?.let(::idsOf).orEmpty()
        }

        private fun idsOf(entry: JsonObject): List<NetId> {
            val ids = entry["ids"] ?: throw IllegalArgumentException("a selection answer has no `ids` member: $entry")
            return ids.jsonArray.map { NetId.ofRaw(it.jsonPrimitive.int) }
        }
    }
}
