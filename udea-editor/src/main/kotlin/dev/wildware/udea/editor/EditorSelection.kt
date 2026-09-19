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
 * What the editor's author has selected, as `editor.selection` says (issue #243).
 *
 * The window keeps no selection of its own: this is the tool's answer, read again whenever a command
 * other than its own read has completed - a click in the Scene tab (issue #235), an agent's
 * `editor.select` sent as `session=editor`, or anything else that may have changed it. So a panel
 * that follows [ids] follows every way of selecting, including ones built after it.
 *
 * Render thread, like [EditorTools].
 */
internal class EditorSelection(private val tools: EditorTools) {

    /** The author's selection, in the order `editor.selection` lists it. */
    var ids: List<NetId> by mutableStateOf(emptyList())
        private set

    /** Why the last read failed, until one succeeds. */
    var problem: String? = null
        private set

    private var pending = false
    private var stale = true
    private var seenCompleted = Long.MIN_VALUE

    /** Once per frame, after [EditorTools.frame]. */
    fun frame() {
        if (tools.changedSince(seenCompleted)) stale = true
        seenCompleted = tools.completed
        if (!stale || pending) return
        pending = true
        stale = false
        tools.read(SELECTION) { answer ->
            pending = false
            when (answer) {
                is AgentResult.Ok -> {
                    ids = parse(answer.json)
                    problem = null
                }
                is AgentResult.Failed -> problem = "$SELECTION refused: ${answer.error}"
            }
        }
    }

    override fun toString(): String = "EditorSelection($ids)"

    companion object {
        const val SELECTION = "editor.selection"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The calling author's own selection out of an `editor.selection` answer: the entry whose
         * `author` is the answer's `you`, or none when that author has selected nothing.
         *
         * @throws IllegalArgumentException when [answer] is not the shape `editor.selection` answers
         *   with - loud, because a panel that quietly showed nothing selected after the tool's answer
         *   changed shape would look like a selection that did not take.
         */
        fun parse(answer: String): List<NetId> {
            val root = json.parseToJsonElement(answer) as? JsonObject
                ?: throw IllegalArgumentException("$SELECTION answered something that is not an object: $answer")
            val you = root["you"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("$SELECTION answered with no you member: $answer")
            val authors = root["authors"]?.jsonArray
                ?: throw IllegalArgumentException("$SELECTION answered with no authors member: $answer")
            val mine = authors.firstOrNull { it.jsonObject["author"]?.jsonPrimitive?.content == you } ?: return emptyList()
            return mine.jsonObject.getValue("ids").jsonArray.map { NetId.ofRaw(it.jsonPrimitive.int) }
        }
    }
}
