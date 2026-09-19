package dev.wildware.udea.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.composegl.ui.widget.SceneViewState
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.Tick
import dev.wildware.udea.render.ui.UiScreen
import dev.wildware.udea.render.view.ViewDimension

/** The editor window's two tabs on the world. */
internal enum class EditorTab { Scene, Game }

/**
 * One open editor: the window a launcher shows, and the per-frame work that keeps it current.
 *
 * ```kotlin
 * val views = EditorViews(backend.openSceneView(EditorCamera()), backend.openGameView())
 * val editor = EditorSession(EditorTools(bridge, sessions.intern("editor")), tick = { host.ctx.clock.tick },
 *     paused = { host.time.paused }, spawn = ..., views = views)
 * backend.show(ui); ui.show(editor.window)
 * backend.drive { delta -> loop.pump(delta); editor.frame() }
 * ```
 *
 * ## A screen over the tool surface
 *
 * The window holds no model of the world and no record of its own edits. Its buttons are tool calls
 * through [EditorTools]; its History panel is `editor.history`'s answer for the editor's author.
 *
 * ## Two tabs on the world (issue #234)
 *
 * The world is shown through [views], one tab at a time. The **Game** tab is the capturable frame -
 * exactly what an agent's screenshot holds - and its pointer is the game's: the window takes no event
 * over it. The **Scene** tab is the same world at the same tick through the editor's own camera, and
 * its pointer is the editor's ([SceneNavigation]). The panels are drawn into the window, never into
 * either view, so no screenshot ever contains them.
 *
 * ## What it does not do
 *
 * It does not pause the world: a launcher starts the editor paused, before the first frame, so no
 * tick runs between boot and the window appearing (issue #194). It does not pick or select anything
 * in the Scene tab: that is epic #231's.
 *
 * @param tick the simulation's tick, read once a frame for the status line and the viewport.
 * @param paused whether the simulation is paused, read once a frame for the status line.
 * @param views the Scene and Game tabs' views of the world.
 * @param standalone starts a separate game on a saved level, for Play standalone.
 */
public class EditorSession(
    private val tools: EditorTools,
    private val tick: () -> Tick,
    private val paused: () -> Boolean,
    private val spawn: EditorSpawn,
    internal val views: EditorViews,
    /** What Play standalone hands the saved level to; `null` leaves that button out (issue #196). */
    standalone: StandaloneLauncher? = null,
) {

    /** The toolbar's Play, Stop, Step and Play standalone. */
    internal val playback: PlayControls = PlayControls(tools, standalone)

    /** The Scene tab's picture. Held here rather than remembered, because [frame] invalidates it. */
    internal val sceneState: SceneViewState = SceneViewState()

    /** The Game tab's picture. */
    internal val gameState: SceneViewState = SceneViewState()

    /** The Scene tab's pointer: gizmos, then the editor camera. */
    internal val navigation: SceneNavigation = SceneNavigation(views.scene)

    /** Which tab is showing. The Scene tab first: an editor opens on the editor's view. */
    internal var tab: EditorTab by mutableStateOf(EditorTab.Scene)
        private set

    /** Whether the Game tab draws gizmos over the game. Off until its toggle is turned on. */
    internal var gameGizmos: Boolean by mutableStateOf(views.game.showGizmos)
        private set

    /** Which camera a drag in the Scene tab moves. */
    internal var dimension: ViewDimension by mutableStateOf(views.camera.dimension)
        private set

    /** What the History panel lists: the editor author's undo history, newest first. */
    internal var history: List<HistoryEntry> by mutableStateOf(emptyList())
        private set

    /** The Asset panel: an asset's values, and File > Save (issue #195). */
    internal val assets: EditorAssets = EditorAssets(tools)

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

    /** Frames the Scene tab is still drawn again for after its camera moved: see [SCENE_MOVED_FRAMES]. */
    private var sceneMoved = 0

    /** The views' sizes when [frame] last looked, so a resize draws the tabs again. */
    private var seenSceneWidth = 0
    private var seenSceneHeight = 0
    private var seenGameWidth = 0
    private var seenGameHeight = 0

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
        // Both asked every frame: each keeps what it last saw.
        val resized = resized()
        val due = redraw.due(tick(), completed) || resized
        if (navigation.consumeMoved()) sceneMoved = SCENE_MOVED_FRAMES
        if (due || sceneMoved > 0) sceneState.invalidate()
        if (due) gameState.invalidate()
        if (sceneMoved > 0) sceneMoved--
        status = statusLine()
    }

    /**
     * True when either view has taken a new size since the last frame (issue #234). A view is drawn at
     * the size of the tab a frame after the tab asks for it, and a paused world asks the tab to draw
     * nothing new, so without this the tab would keep the picture it had at the old size.
     */
    private fun resized(): Boolean {
        val scene = views.scene
        val game = views.game
        val changed = scene.width != seenSceneWidth || scene.height != seenSceneHeight ||
            game.width != seenGameWidth || game.height != seenGameHeight
        seenSceneWidth = scene.width
        seenSceneHeight = scene.height
        seenGameWidth = game.width
        seenGameHeight = game.height
        return changed
    }

    /** The Scene tab's `SceneView` size, in layout units: see [scenePointer]. */
    internal var sceneBox: Size = Size.Zero

    /**
     * A pointer event over the Scene tab, positioned in its picture's pixels, handed to [navigation].
     *
     * The picture's size is the `SceneViewState`'s once it has been rendered. Before that - and always,
     * with no GL - the `SceneView` reports a position in its own layout units, one to a pixel, so its
     * layout size is the picture's size for as long as that holds.
     */
    internal fun scenePointer(event: PointerEvent): Boolean {
        val rendered = sceneState.width > 0 && sceneState.height > 0
        val width = if (rendered) sceneState.width else sceneBox.width.toInt()
        val height = if (rendered) sceneState.height else sceneBox.height.toInt()
        return navigation.onPointer(event, width, height)
    }

    /** Shows [tab]. */
    internal fun show(tab: EditorTab) {
        if (tab == this.tab) return
        this.tab = tab
        // The picture a tab shows was last drawn when it was last shown; draw it again now.
        sceneState.invalidate()
        gameState.invalidate()
    }

    /** The Game tab's gizmo toggle: draws them over the game, read-only. */
    internal fun showGameGizmos(show: Boolean) {
        views.game.showGizmos = show
        gameGizmos = show
        gameState.invalidate()
    }

    /** The Scene tab's 2D / 3D switch. */
    internal fun switchDimension(dimension: ViewDimension) {
        views.camera.dimension = dimension
        this.dimension = dimension
        navigation.markMoved()
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

    /**
     * Copies [tab]'s view into the picture [scope] is drawing.
     *
     * Both views are sized to the picture, not only the one showing: the two tabs share one
     * rectangle, and the Game view's size is the game's own frame (issue #234), which an agent's
     * `render.screenshot` reads whichever tab a person is looking at.
     */
    internal fun drawView(tab: EditorTab, scope: SceneDrawScope) {
        views.scene.resizeTo(scope.width, scope.height)
        views.game.resizeTo(scope.width, scope.height)
        when (tab) {
            EditorTab.Scene -> views.scene.drawInto(scope)
            EditorTab.Game -> views.game.drawInto(scope)
        }
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

        /**
         * Frames the Scene tab's picture is copied again after its camera moves. Two, not one: a
         * pointer event is handled while the window is drawn, which can be after the pipeline drew
         * the Scene view for this frame, so only the next frame's view is sure to hold the move.
         */
        const val SCENE_MOVED_FRAMES: Int = 2
    }
}
