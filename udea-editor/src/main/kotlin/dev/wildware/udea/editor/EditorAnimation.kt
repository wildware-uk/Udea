package dev.wildware.udea.editor

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.render.model.ImportedModel
import dev.wildware.udea.render.model.ModelRenderSystem

/**
 * What a game hands the editor's Animation panel (issue #243): its animated models with their
 * generated clips, the world to find a selected entity's model in, and the renderer that draws it.
 *
 * ```kotlin
 * val animation = EditorAnimation(host.world, netIds, listOf(AnimatedModel(fox, Fox.Clips.all)), models)
 * EditorSession(tools, tick, paused, spawn, views, animation = animation)
 * ```
 *
 * The clips are the ones the asset build generated from each model's file (`Fox.Clips.all`, issue
 * #241), so the panel lists exactly what gameplay code can name.
 *
 * @param renderer draws the models, and says where their joints are for the bone overlay; `null`
 *   draws no overlay - an editor with no render context, such as a headless test.
 */
public class EditorAnimation(
    internal val world: World,
    internal val netIds: NetIdIndex,
    internal val models: List<AnimatedModel>,
    internal val renderer: ModelRenderSystem?,
) {
    init {
        val ids = models.map { it.model.asset.id }
        require(ids.distinct().size == ids.size) { "a model is listed twice: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}" }
    }

    override fun toString(): String = "EditorAnimation(${models.map { it.model.asset.id }})"
}

/** One model a game can animate, and the clips its asset build generated for it. */
public class AnimatedModel(
    public val model: ImportedModel,
    /** Every clip in the file, in the file's order: the generated `Clips.all`. */
    public val clips: List<AnimationClip>,
) {
    init {
        require(clips.isNotEmpty()) { "${model.asset.id} has no clips, so there is nothing to animate" }
    }

    override fun toString(): String = "AnimatedModel(${model.asset.id}, ${clips.map { it.name }})"
}
