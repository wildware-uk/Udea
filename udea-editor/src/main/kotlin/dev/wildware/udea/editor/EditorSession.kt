package dev.wildware.udea.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.composegl.ui.widget.SceneViewState
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.Tick
import dev.wildware.udea.render.ui.UiScreen

/**
 * One open editor: the window a launcher shows, and the per-frame work that keeps it current.
 *
 * ```kotlin
 * val editor = EditorSession(EditorTools(bridge, sessions.intern("editor")), tick = { host.ctx.clock.tick },
 *     paused = { host.time.paused }, spawn = ..., viewport = { world.drawInto(this) })
 * backend.show(ui); ui.show(editor.window)
 * backend.drive { delta -> loop.pump(delta); editor.frame() }
 * ```
 *
 * ## A screen over the tool surface
 *
 * The window holds no model of the world and no record of its own edits. Its buttons are tool calls
 * through [EditorTools]; its History panel is `editor.history`'s answer for the editor's author; its
 * viewport is whatever [viewport] draws, which for a real launcher is `udea-render`'s `WorldView` -
 * the capturable frame, so the viewport shows exactly what an agent's screenshot holds. The panels
 * are drawn into the window, never into that frame, so no screenshot ever contains them.
 *
 * ## What it does not do
 *
 * It does not pause the world: a launcher starts the editor paused, before the first frame, so no
 * tick runs between boot and the window appearing (issue #194). It does not hit-test or move anything
 * in the viewport: picking, selection and gizmos are epic #231.
 *
 * @param tick the simulation's tick, read once a frame for the status line and the viewport.
 * @param paused whether the simulation is paused, read once a frame for the status line.
 * @param viewport draws the world into the viewport's picture, after it has been cleared.
 * @param standalone starts a separate game on a saved level, for Play standalone.
 */
public class EditorSession(
    private val tools: EditorTools,
    private val tick: () -> Tick,
    private val paused: () -> Boolean,
    private val spawn: EditorSpawn,
    private val viewport: SceneDrawScope.() -> Unit,
    /** What Play standalone hands the saved level to; `null` leaves that button out (issue #196). */
    standalone: StandaloneLauncher? = null,
) {

    /** The toolbar's Play, Stop, Step and Play standalone. */
    internal val playback: PlayControls = PlayControls(tools, standalone)

    /** The viewport's picture. Held here rather than remembered, because [frame] invalidates it. */
    internal val viewportState: SceneViewState = SceneViewState()

    /** What the History panel lists: the editor author's undo history, newest first. */
    internal var history: List<HistoryEntry> by mutableStateOf(emptyList())
        private set

    /** The status line. */
    internal var status: String by mutableStateOf("")
        private set

    /** The last refusal an editor action got, until the next action succeeds. */
    private var problem: String? = null

    private val redraw = ViewportRedraw()

    /** The id of the latest `editor.history` read this session sent, or `null` before the first. */
    private var historyRead: Long? = null

    private var historyPending = false

    private var historyStale = true

    private var seenCompleted = Long.MIN_VALUE

    /** The window: menus, docked panels, the viewport and the status line. Show it on a `UiLayer`. */
    public val window: UiScreen = object : UiScreen {
        @Composable
        override fun content() {
            EditorWindow(this@EditorSession)
        }
    }

    /**
     * Keeps the window current. Once per frame, on the render thread, after the frame's commands have
     * run - in a launcher, right after `AgentGameLoop.pump`.
     *
     * Delivers answers, re-reads the history when any command other than its own read has completed
     * since the last one (the editor's edits and an agent's alike), invalidates the viewport when the
     * world may have changed ([ViewportRedraw]), and rewrites the status line.
     */
    public fun frame() {
        tools.frame()
        val completed = tools.completed
        if (completed != seenCompleted) {
            if (completed != historyRead) historyStale = true
            seenCompleted = completed
        }
        if (historyStale && !historyPending) readHistory()
        if (redraw.due(tick(), completed)) viewportState.invalidate()
        status = statusLine()
    }

    /** The Create panel's button. */
    internal fun spawn() {
        edit(
            SPAWN,
            mapOf("blueprint" to spawn.blueprint.value, "x" to spawn.x.toString(), "y" to spawn.y.toString()),
        )
    }

    /** The History panel's Undo, and Edit > Undo. */
    internal fun undo() {
        edit(UNDO, emptyMap())
    }

    internal val spawnLabel: String get() = spawn.label

    /** Draws the world into the viewport's picture. */
    internal fun drawViewport(scope: SceneDrawScope) {
        scope.viewport()
    }

    private fun edit(tool: String, args: Map<String, String>) {
        tools.call(tool, args) { answer ->
            problem = when (answer) {
                is AgentResult.Ok -> null
                is AgentResult.Failed -> "$tool refused: ${answer.error}"
            }
        }
    }

    private fun readHistory() {
        historyPending = true
        historyStale = false
        historyRead = tools.call(HISTORY, mapOf("limit" to HISTORY_LIMIT.toString())) { answer ->
            historyPending = false
            when (answer) {
                is AgentResult.Ok -> history = HistoryEntry.parse(answer.json)
                is AgentResult.Failed -> problem = "$HISTORY refused: ${answer.error}"
            }
        }
    }

    private fun statusLine(): String = buildString {
        append(if (paused()) "Paused" else "Running")
        append(" - tick ")
        append(tick().value)
        problem?.let {
            append(" - ")
            append(it)
        }
    }

    override fun toString(): String = "EditorSession($tools, $spawn)"

    private companion object {
        const val SPAWN = "editor.spawn"
        const val UNDO = "editor.undo"
        const val HISTORY = "editor.history"

        /** How many edits the History panel lists: a screenful, newest first. */
        const val HISTORY_LIMIT: Int = 20
    }
}
