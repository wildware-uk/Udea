package dev.wildware.udea.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.ClipPlayback
import dev.wildware.udea.editor.gizmo.BoneOverlayGizmo
import dev.wildware.udea.editor.gizmo.GizmoMarkLayer
import dev.wildware.udea.editor.gizmo.modelSkeletons
import dev.wildware.udea.render.model.ImportedModel
import dev.wildware.udea.render.model.ModelPreview
import dev.wildware.udea.render.model.ModelRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Animation panel's state and what its controls do (issue #243). The panel is
 * [AnimationPanel]; this is everything behind it, so a test can press its buttons.
 *
 * ## Three things, each on a different side of the simulation
 *
 * - **Choosing a clip is an edit.** It sets the selected entity's `Animator` - the clip and its
 *   length - through an `editor.*` edit session: `begin_edit`, `update_edit`, `commit_edit`, filed
 *   under the editor's author. Two fields, and **one** undo entry, which `editor.undo` reverses; an
 *   agent sending the same calls with `session=editor` makes the same edit. Nothing here writes the
 *   world itself.
 * - **The scrub preview is a view setting.** Play, pause and the scrubber pose the entity in the
 *   Scene tab alone ([ModelPreview.Pose] on the Scene view), and the world is never written: the
 *   Game tab and `render.screenshot` keep the simulated pose, and leaving the preview shows it again.
 *   Playing steps the preview one clip tick per editor frame - a frame count, not a clock, so the
 *   preview is the same wherever it runs.
 * - **The bone overlay is a gizmo.** The panel puts a [GizmoMarkLayer] with the [BoneOverlayGizmo]
 *   on the Scene tab, so the selection's skeleton is drawn there, in the preview's pose while one is
 *   on. It replaces whatever layer the Scene view had. Not on the Game tab: a gizmo there is placed
 *   through the game's 2D camera (`GizmoCanvas`), and a model is drawn through its own 3D one, so
 *   the joints would be drawn somewhere the model is not.
 *
 * The selection is `editor.selection`'s for the editor's author ([EditorSelection]): the panel
 * follows whatever selected the entity - a click, or an agent's `editor.select`.
 *
 * Selecting a model in the panel's model list previews that model on its own, turning at the Scene
 * tab's centre ([ModelPreview.Asset]), with its clips.
 */
internal class AnimationPanelState(
    private val animation: EditorAnimation,
    private val tools: EditorTools,
    private val views: EditorViews,
    /** Says the Scene tab's picture has changed without the world changing: a preview moved. */
    private val sceneChanged: () -> Unit,
) {

    private val selection = EditorSelection(tools)

    /** The selection the Scene tab last drew the bone overlay for. */
    private var drawnSelection: List<NetId> = emptyList()

    /** The selected entity the panel shows: the first one drawn with an animated model. */
    var target: NetId? by mutableStateOf(null)
        private set

    /** [target]'s model and clips. */
    var targetModel: AnimatedModel? by mutableStateOf(null)
        private set

    /** The clip [target]'s `Animator` is playing, by index, or [ClipPlayback.NO_CLIP]. */
    var playingClip: Int by mutableStateOf(ClipPlayback.NO_CLIP)
        private set

    /** Whether the Scene tab shows [target] in [previewClip] at [previewAt] instead of as simulated. */
    var previewing: Boolean by mutableStateOf(false)
        private set

    /** The clip the preview shows. */
    var previewClip: AnimationClip? by mutableStateOf(null)
        private set

    /** Where in [previewClip] the preview is, in clip ticks: what the scrubber shows. */
    var previewAt: Long by mutableStateOf(0L)
        private set

    /** Whether the preview moves on by itself, one clip tick a frame. */
    var playing: Boolean by mutableStateOf(false)
        private set

    /** The model shown on its own, turning, or `null`. */
    var assetPreview: AnimatedModel? by mutableStateOf(null)
        private set

    /** The last refusal a control got, until the next one succeeds. */
    var problem: String? by mutableStateOf(null)
        private set

    val models: List<AnimatedModel> get() = animation.models

    /** How far the model preview has turned, in degrees. Presentation only. */
    private var turn = 0f

    init {
        val renderer = animation.renderer
        if (renderer != null) {
            val bones = BoneOverlayGizmo(modelSkeletons(renderer, views.scene))
            views.scene.gizmos = GizmoMarkLayer(animation.world, animation.netIds, selection = { selection.ids }, gizmos = listOf(bones))
        }
    }

    /** Once per frame, after [EditorTools.frame]. */
    fun frame() {
        selection.frame()
        // The Scene tab draws only when something says its picture changed, and the bone overlay
        // draws the selection: a new one is a new picture.
        if (selection.ids != drawnSelection) {
            drawnSelection = selection.ids
            sceneChanged()
        }
        follow()
        if (playing) {
            val clip = previewClip
            if (clip != null) previewAt = if (previewAt >= clip.length.count) 0L else previewAt + 1
        }
        if (assetPreview != null) turn = (turn + TURN_DEGREES_PER_FRAME) % FULL_TURN
        apply()
    }

    /** Chooses [clip] for [target]'s `Animator`: one edit, through the tools. */
    fun choose(clip: AnimationClip) {
        val entity = target ?: return
        if (previewing) previewClip = clip
        tools.call(BEGIN_EDIT, mapOf("entities" to entity.raw.toString(), "fields" to "$CLIP_FIELD,$LENGTH_FIELD")) { begun ->
            val session = answered(BEGIN_EDIT, begun) ?: return@call
            val id = Json.parseToJsonElement(session).jsonObject.getValue("sessionId").jsonPrimitive.int.toString()
            val values = "$CLIP_FIELD=${clip.index},$LENGTH_FIELD=${clip.length.count}"
            tools.call(UPDATE_EDIT, mapOf("sessionId" to id, "values" to values)) { updated ->
                answered(UPDATE_EDIT, updated) ?: return@call
                tools.call(COMMIT_EDIT, mapOf("sessionId" to id)) { committed -> answered(COMMIT_EDIT, committed) }
            }
        }
    }

    /** Turns the scrub preview on - on the clip playing now, at its start - or off. */
    fun preview(on: Boolean) {
        val model = targetModel
        if (!on || model == null) {
            previewing = false
            playing = false
            return
        }
        assetPreview = null
        previewing = true
        previewClip = model.clips.firstOrNull { it.index == playingClip } ?: model.clips.first()
        previewAt = 0L
    }

    /** Plays or pauses the preview. */
    fun play(on: Boolean) {
        if (on && !previewing && assetPreview == null) preview(true)
        playing = on
    }

    /** Puts the preview [at] clip ticks into its clip, pausing it: the scrubber. */
    fun scrub(at: Long) {
        val clip = previewClip ?: return
        playing = false
        previewAt = at.coerceIn(0L, clip.length.count)
    }

    /** Shows [clip] in the preview - the entity's, or the model preview's - from its start. */
    fun previewOf(clip: AnimationClip) {
        if (assetPreview == null && !previewing) preview(true)
        previewClip = clip
        previewAt = 0L
    }

    /** Shows [model] on its own, turning, or stops showing it when it already is. */
    fun showModel(model: AnimatedModel) {
        if (assetPreview === model) {
            assetPreview = null
            playing = false
            return
        }
        previewing = false
        assetPreview = model
        previewClip = model.clips.first()
        previewAt = 0L
        playing = true
    }

    /** Keeps [target] on the first selected entity drawn with an animated model. */
    private fun follow() {
        var found: NetId? = null
        var model: AnimatedModel? = null
        var clip = ClipPlayback.NO_CLIP
        with(animation.world) {
            for (id in selection.ids) {
                val entity = animation.netIds.resolveOrNull(id) ?: continue
                val drawn = entity.getOrNull(ModelRenderer)?.model as? ImportedModel ?: continue
                model = animation.models.firstOrNull { it.model.asset.id == drawn.asset.id } ?: continue
                found = id
                clip = entity.getOrNull(Animator)?.current?.clip ?: ClipPlayback.NO_CLIP
                break
            }
        }
        if (found != target) {
            // Another entity, or none: its preview does not carry over.
            previewing = false
            if (assetPreview == null) playing = false
            target = found
        }
        targetModel = model
        playingClip = clip
    }

    /** Puts this frame's preview on the Scene view, and says so when it changed the picture. */
    private fun apply() {
        val entity = target
        val clip = previewClip
        val asset = assetPreview
        val wanted: ModelPreview? = when {
            asset != null -> ModelPreview.Asset(asset.model, clip, Ticks(previewAt), turn)
            previewing && entity != null && clip != null -> ModelPreview.Pose(entity, clip, Ticks(previewAt))
            else -> null
        }
        if (wanted != views.scene.modelPreview) {
            views.scene.modelPreview = wanted
            sceneChanged()
        }
    }

    /** [answer]'s JSON when it succeeded; otherwise records why [tool] refused and answers `null`. */
    private fun answered(tool: String, answer: AgentResult): String? = when (answer) {
        is AgentResult.Ok -> {
            problem = null
            answer.json
        }
        is AgentResult.Failed -> {
            problem = "$tool refused: ${answer.error}"
            null
        }
    }

    override fun toString(): String = "AnimationPanelState(target=$target, previewing=$previewing, at=$previewAt)"

    private companion object {
        const val BEGIN_EDIT = "editor.begin_edit"
        const val UPDATE_EDIT = "editor.update_edit"
        const val COMMIT_EDIT = "editor.commit_edit"

        /** The two `Animator` fields a clip is: which clip, and how long it runs. */
        const val CLIP_FIELD = "Animator.current.clip"
        const val LENGTH_FIELD = "Animator.current.length"

        /** How far the model preview turns each frame: a full turn in six seconds at 60 frames a second. */
        const val TURN_DEGREES_PER_FRAME = 1f
        const val FULL_TURN = 360f
    }
}
