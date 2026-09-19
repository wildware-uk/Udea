package dev.wildware.udea.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.identity.NetId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One value a play edit left: on which entity, which `Component.field`, and the value as text. */
internal class PlayEditValue(val netId: Int, val key: String, val value: String) {
    override fun toString(): String = "#$netId $key=$value"
}

/**
 * One edit made during the play under way, as `editor.play_edits` lists it.
 *
 * @property reason why it cannot be kept, or `null` when it can.
 */
internal class PlayEditRow(
    val editId: Long,
    val author: String,
    val tool: String,
    val netId: Int,
    val keepable: Boolean,
    val kept: Boolean,
    val reason: String?,
    val values: List<PlayEditValue>,
) {

    /** Whether this edit wrote [key] on the entity [netId] names. */
    fun wrote(netId: NetId, key: String): Boolean = values.any { it.netId == netId.raw && it.key == key }

    override fun toString(): String = "#$editId $tool by $author${if (kept) ", kept" else ""}"
}

/**
 * The "Changes during Play" panel's state and the Inspector's Keep pins (issue #238).
 *
 * ## A screen over the tool surface
 *
 * What it lists is `editor.play_edits`' answer, and a toggle is `editor.keep` or `editor.unkeep`: an
 * agent keeping an edit and a person ticking its box are the same call, and each sees the other's.
 * It keeps no copy of what is kept beyond the last answer.
 *
 * ## When it reads
 *
 * Again whenever anything but the editor's own reads has completed - an edit, a keep, Play, Stop, the
 * editor's or an agent's - since a play edit only ever comes or goes through a tool call. One read
 * at a time.
 *
 * Render thread only, like [EditorTools].
 */
internal class EditorPlayEdits(
    private val tools: EditorTools,
    /** Told why a keep, an unkeep or a read was refused. */
    private val refused: (String) -> Unit,
) {

    /** Whether a play is under way, as the last read found. */
    var playing: Boolean by mutableStateOf(false)
        private set

    /** The play edits, oldest first, as the last read listed them. */
    var edits: List<PlayEditRow> by mutableStateOf(emptyList())
        private set

    private var stale = true
    private var pending = false

    /** Reads the play edits again if they may have changed. Once a frame, after the tools have delivered their answers. */
    fun frame(changed: Boolean) {
        if (changed) stale = true
        if (!stale || pending) return
        stale = false
        pending = true
        tools.read(PLAY_EDITS) { answer ->
            pending = false
            when (answer) {
                is AgentResult.Ok -> {
                    val root = json.parseToJsonElement(answer.json).jsonObject
                    playing = root.getValue("playing").jsonPrimitive.boolean
                    edits = parse(answer.json)
                }
                is AgentResult.Failed -> refused("$PLAY_EDITS refused: ${answer.error}")
            }
        }
    }

    /** The panel's toggle: keeps [row], or stops keeping it. */
    fun setKept(row: PlayEditRow, keep: Boolean) {
        val tool = if (keep) KEEP else UNKEEP
        tools.call(tool, mapOf("editId" to row.editId.toString())) { answer ->
            if (answer is AgentResult.Failed) refused("$tool refused: ${answer.error}")
        }
    }

    /** The play edits that wrote [key] on any of [selected], oldest first. */
    fun touching(selected: List<NetId>, key: String): List<PlayEditRow> =
        edits.filter { row -> selected.any { row.wrote(it, key) } }

    /**
     * The Inspector's pin on [key] over [selected]: keeps every keepable play edit that wrote the field
     * on the selection when none of them is kept, and stops keeping them all when one is. Every one,
     * not only the newest, because Stop makes kept edits again oldest first and a field written on two
     * selected entities by two edits needs both for each entity to keep its own final value.
     */
    fun togglePin(selected: List<NetId>, key: String) {
        val rows = touching(selected, key)
        val keep = rows.none { it.kept }
        for (row in rows) if (row.keepable && row.kept != keep) setKept(row, keep)
    }

    override fun toString(): String = "EditorPlayEdits(${edits.size} edits${if (playing) ", playing" else ""})"

    internal companion object {
        const val PLAY_EDITS = "editor.play_edits"
        const val KEEP = "editor.keep"
        const val UNKEEP = "editor.unkeep"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * `editor.play_edits`' answer as panel rows.
         *
         * @throws IllegalArgumentException when it is not that shape: loud, for `HistoryEntry.parse`'s
         *   reason - a panel that quietly listed nothing after the answer changed shape would read as a
         *   play with no edits, and a Stop would drop what somebody meant to keep.
         */
        fun parse(answer: String): List<PlayEditRow> {
            val root = json.parseToJsonElement(answer) as? JsonObject
                ?: throw IllegalArgumentException("$PLAY_EDITS answered something that is not an object: $answer")
            val edits = root["edits"] ?: throw IllegalArgumentException("$PLAY_EDITS answered with no edits member: $answer")
            return edits.jsonArray.map { element ->
                val edit = element.jsonObject
                PlayEditRow(
                    editId = edit.getValue("editId").jsonPrimitive.long,
                    author = edit.getValue("author").jsonPrimitive.content,
                    tool = edit.getValue("tool").jsonPrimitive.content,
                    netId = edit.getValue("id").jsonPrimitive.int,
                    keepable = edit.getValue("keepable").jsonPrimitive.boolean,
                    kept = edit.getValue("kept").jsonPrimitive.boolean,
                    reason = edit["reason"]?.jsonPrimitive?.content,
                    values = edit["values"]?.jsonArray.orEmpty().map { written ->
                        val value = written.jsonObject
                        PlayEditValue(
                            netId = value.getValue("id").jsonPrimitive.int,
                            key = "${value.getValue("component").jsonPrimitive.content}.${value.getValue("field").jsonPrimitive.content}",
                            value = when (val shown = value["value"]) {
                                null -> ""
                                is JsonPrimitive -> shown.content
                                else -> shown.toString()
                            },
                        )
                    },
                )
            }
        }
    }
}
