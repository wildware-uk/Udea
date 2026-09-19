package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.agent.dispatch.AgentContext
import dev.wildware.udea.agent.tools.ContextualToolDef
import dev.wildware.udea.core.host.RenderMode
import java.util.concurrent.Future
import kotlin.reflect.KClass

/** One of the editor window's two looks at the world (issue #234). */
public enum class EditorView(
    /** How an agent names it: the `view` argument of `editor.screenshot`. */
    public val wireName: String,
) {
    /** The world through the editor's own camera, gizmos drawn. */
    Scene("scene"),

    /** The capturable frame through the game's camera, with gizmos only when its toggle is on. */
    Game("game"),
    ;

    public companion object {

        /** The view [name] names, or `null`. */
        public fun of(name: String): EditorView? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * What `editor.screenshot` needs from an editor window's views: a capture of the next frame of one.
 *
 * A port for the same reason [RenderControl] is one: `udea-render` may not name this module, so the
 * arrow points this way. `render.WorldViewportControl` is the implementation over `udea-render`'s
 * `WorldViewport`s.
 */
public fun interface EditorViewControl {

    /**
     * Queues a capture of the next frame of [view] - as the window shows it, gizmos included - and
     * returns at once. The renderer settles the future after a frame has drawn it; a failure arrives
     * as an `ExecutionException`.
     */
    public fun capture(view: EditorView): Future<CaptureFrame>
}

/**
 * `editor.screenshot`: a picture of the editor window's Scene or Game tab (issue #234).
 *
 * `render.screenshot` reads the capturable frame, which never holds a gizmo; this reads an editor
 * view's own pass, which does. So an agent sees what a person at the editor sees - handles and all -
 * while every picture it diffs through `render.*` stays gizmo-free.
 *
 * ## Bound late
 *
 * The tool index is built when the agent host attaches, and the editor window opens its views after
 * that, on a backend that is already running. So the views are [bind]-ed when the window opens, and
 * until then the tool answers `no_editor_window`: an editor instance run with no window (`-Peditor=true`
 * on a plain run) has `editor.*` and no views, which is a fact to report rather than a fault to hide.
 *
 * @param mode `Headless` refuses with `no_render_context`, as every capture tool does.
 */
public class EditorViewToolset(
    private val mode: RenderMode,
    artifacts: AgentArtifacts?,
    captureGraceMillis: Long = CaptureFiling.DEFAULT_GRACE_MILLIS,
) {

    private val filing = CaptureFiling(artifacts, captureGraceMillis)

    /** The window's views. Written once on the render thread when the window opens; read there too. */
    @Volatile
    private var views: EditorViewControl? = null

    /** Hands over the window's views, once they exist. */
    public fun bind(views: EditorViewControl) {
        check(this.views == null) { "$this is already bound to an editor window's views" }
        this.views = views
    }

    /**
     * Captures [view] - `scene` or `game` - and files it.
     *
     * @return `null` once queued, as `render.screenshot` does; a typed failure otherwise.
     */
    public fun screenshot(view: String, context: AgentContext): AgentResult? {
        if (mode == RenderMode.Headless) {
            return AgentResult.failed(
                AgentHostErrors.NO_RENDER_CONTEXT,
                "this process runs in RenderMode.Headless: there is no editor window and no GL context to read",
            )
        }
        val chosen = EditorView.of(view) ?: return AgentResult.failed(
            AgentErrorKind.BAD_ARGUMENT,
            "view '$view' is not one of ${EditorView.entries.joinToString { it.wireName }}",
        )
        val bound = views ?: return AgentResult.failed(
            AgentHostErrors.NO_EDITOR_WINDOW,
            "this instance has the editor tools but no editor window, so there is no Scene or Game tab to " +
                "capture. Start it with :moba:desktop:runEditor; render.screenshot still captures the game.",
        )
        return filing.answer(context, request = { bound.capture(chosen) }) {
            put("view", chosen.wireName)
        }
    }

    override fun toString(): String = "EditorViewToolset($mode, bound=${views != null})"
}

/**
 * `editor.screenshot`, as a [ToolModule]. Hand-written and registered by the host that opens an editor
 * window, like [AgentHostTools], because its toolset needs the artifact store and the late-bound views.
 */
public object EditorViewTools : ToolModule {

    override val moduleName: String = "UdeaEditorViews"

    override val tools: List<AgentToolDef<*>> = listOf(EditorScreenshotTool)
}

/** `editor.screenshot`. */
public object EditorScreenshotTool : ContextualToolDef<EditorViewToolset> {

    override val name: String = "editor.screenshot"

    override val description: String = "Capture the editor window's Scene or Game tab as a PNG and file it " +
        "in the artifact store - what a person at the editor sees, gizmos included. view=scene is the world " +
        "through the editor's own camera; view=game is the game's picture, with gizmos over it only when the " +
        "Game tab's toggle is on. render.screenshot never holds a gizmo; use it for pictures you diff. " +
        "Returns the path, the artifact id and the tick the frame was drawn at. Answers no_editor_window " +
        "when no editor window is open, and no_render_context in Headless."

    override val args: List<AgentToolArg> = listOf(
        AgentToolArg("view", "string", "Which tab: scene or game.", required = true, default = null),
    )

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = EditorViewToolset::class

    override fun invoke(receiver: EditorViewToolset, command: AgentCommand, context: AgentContext): Any? =
        receiver.screenshot(command.str("view"), context)

    override fun invoke(receiver: EditorViewToolset, command: AgentCommand): Any? =
        throw UnsupportedOperationException(
            "$name answers after the tick and needs the AgentContext of the command it is serving; call the " +
                "three-argument invoke, which is what ToolIndex does",
        )
}
