package dev.wildware.hollow.render

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.hollow.Player
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.ModelSource

/**
 * Gives every character the human model to be drawn with (issue #250).
 *
 * [ScenerySystem] is the same step for the clearing's props, and the reason is the same: a level, a
 * snapshot and a headless server have no business holding a model, so the simulation's half of a
 * character is a `Player`, a `Transform3D` and an `Animator`, and presentation attaches the rest.
 * A character that arrived over the wire a moment ago gets its model on the next frame, with
 * nothing to keep in step.
 *
 * `ModelRenderSystem` poses the model from the entity's `Animator` (issue #242), so nothing here
 * touches the animation: [dev.wildware.hollow.PlayerPoseSystem] chooses the clip, on the server,
 * and it reaches every client as replicated state.
 *
 * A frame in which nothing changed allocates nothing: the only write is for a character that has no
 * renderer yet.
 *
 * @param human the model every character is drawn with. Asked for once per character, not per frame.
 */
internal class CharacterSystem(private val human: () -> ModelSource) : RenderSystem {

    private var bound: Bound? = null

    override fun onBind(world: World, ctx: GameContext) {
        bound = Bound(world, world.family { all(Player).none(ModelRenderer) })
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        sync()
    }

    /** Gives every character with no model one. Render thread. */
    internal fun sync() {
        val bound = bound ?: return
        with(bound.world) {
            bound.characters.forEach { entity ->
                entity.configure { it += ModelRenderer(human()) }
            }
        }
    }

    override fun toString(): String = "CharacterSystem"

    private class Bound(val world: World, val characters: Family)
}
