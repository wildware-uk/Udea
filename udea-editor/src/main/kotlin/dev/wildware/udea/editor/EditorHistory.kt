package dev.wildware.udea.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * One line of an author's undo history, as `editor.history` lists it.
 *
 * The panel shows these and nothing else: it keeps no record of its own of what the editor did, so
 * what it shows is what `editor.undo` would undo, for the same author, by construction.
 */
internal class HistoryEntry(
    /** The edit's place in the process-wide order `EditorHistory` numbers edits in. */
    val sequence: Long,
    /** The tool that made it, e.g. `editor.spawn`. */
    val tool: String,
    /** The raw `NetId` of the entity it touched. */
    val netId: Int,
) {
    override fun toString(): String = "#$sequence $tool entity #$netId"

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The edits in an `editor.history` answer, newest first as the tool lists them.
         *
         * @throws IllegalArgumentException when [answer] is not the shape `editor.history` answers
         *   with. Loud, because a panel that quietly showed an empty history after the tool's answer
         *   changed shape would read as "nothing to undo".
         */
        fun parse(answer: String): List<HistoryEntry> {
            val root = json.parseToJsonElement(answer) as? JsonObject
                ?: throw IllegalArgumentException("editor.history answered something that is not an object: $answer")
            val edits = root["edits"]
                ?: throw IllegalArgumentException("editor.history answered with no edits member: $answer")
            return edits.jsonArray.map { element ->
                val edit = element.jsonObject
                HistoryEntry(
                    sequence = edit.getValue("sequence").jsonPrimitive.long,
                    tool = edit.getValue("tool").jsonPrimitive.content,
                    netId = edit.getValue("id").jsonPrimitive.int,
                )
            }
        }
    }
}
