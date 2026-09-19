package dev.wildware.udea.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.udea.agent.AgentResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Path

/**
 * Starts a separate game on a level file: what Play standalone hands the saved level to (issue #196).
 *
 * The game's to supply, because only the game knows how it is launched - `moba`'s starts
 * `:moba:desktop:run`'s main class in a new JVM. An editor given none has no Play standalone.
 */
public fun interface StandaloneLauncher {

    /**
     * Starts the game on [level] and returns without waiting for it.
     *
     * @throws IOException when the game could not be started. The toolbar shows the message.
     */
    public fun launch(level: Path)
}

/**
 * The toolbar's Play, Stop, Step and Play standalone, each a tool call through [tools].
 *
 * Play and Stop are `editor.play` and `editor.stop`, and Step is `time.step` with one tick - the same
 * calls an agent makes. The world is never touched from here. What the toolbar says is read from the
 * answers: [status] is the last one's outcome. It keeps no copy of whether a play is under way, because
 * an agent can start or stop one without the window knowing.
 */
internal class PlayControls(
    private val tools: EditorTools,
    private val standalone: StandaloneLauncher?,
) {

    /** One line about what the toolbar last did. */
    var status: String by mutableStateOf(EDITING)
        private set

    /** Whether Play standalone has a launcher to hand the level to. */
    val canPlayStandalone: Boolean get() = standalone != null

    fun play() {
        tools.call(PLAY) { answer ->
            when (answer) {
                is AgentResult.Ok -> {
                    val fields = fields(answer)
                    val returnsTo = fields["stopReturnsTo"] ?: fields["tick"]
                    status = "Playing - Stop returns to tick ${returnsTo?.jsonPrimitive?.content}"
                }
                is AgentResult.Failed -> status = "$PLAY refused: ${answer.error}"
            }
        }
    }

    fun stop() {
        tools.call(STOP) { answer ->
            when (answer) {
                is AgentResult.Ok -> status = "$EDITING - back at tick ${fields(answer)["tick"]?.jsonPrimitive?.content}"
                is AgentResult.Failed -> status = "$STOP refused: ${answer.error}"
            }
        }
    }

    fun step() {
        tools.call(STEP, mapOf("ticks" to "1")) { answer ->
            status = when (answer) {
                is AgentResult.Ok -> "Stepped to tick ${fields(answer)["tickAfter"]?.jsonPrimitive?.content}"
                is AgentResult.Failed -> "$STEP refused: ${answer.error}"
            }
        }
    }

    /**
     * Saves the world through `editor.save`, under [STANDALONE_LEVEL] in the editor's level
     * directory, then hands the file to the launcher. Through the tool, so the file is exactly what
     * a Save writes and the save runs between ticks like every other editor call.
     */
    fun playStandalone() {
        val launcher = standalone ?: return
        tools.call(SAVE, mapOf("name" to STANDALONE_LEVEL)) { answer ->
            status = when (answer) {
                is AgentResult.Failed -> "$SAVE refused: ${answer.error}"
                is AgentResult.Ok -> {
                    val level = Path.of(checkNotNull(fields(answer)["path"]) { "$SAVE answered no path: ${answer.json}" }.jsonPrimitive.content)
                    try {
                        launcher.launch(level)
                        "Standalone game started on ${level.fileName}"
                    } catch (failed: IOException) {
                        "Play standalone could not start the game: ${failed.message}"
                    }
                }
            }
        }
    }

    private fun fields(answer: AgentResult.Ok): JsonObject = json.parseToJsonElement(answer.json) as? JsonObject
        ?: throw IllegalArgumentException("a tool answered something that is not an object: ${answer.json}")

    override fun toString(): String = "PlayControls($status)"

    internal companion object {
        const val PLAY = "editor.play"
        const val STOP = "editor.stop"
        const val STEP = "time.step"
        const val SAVE = "editor.save"

        /** The level name Play standalone saves under, overwritten each time. */
        const val STANDALONE_LEVEL = "play-standalone"

        const val EDITING = "Editing"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
