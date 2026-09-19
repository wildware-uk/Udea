package dev.wildware.hollow.render

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.hollow.Prop
import dev.wildware.hollow.Scenery
import dev.wildware.hollow.Sunlight
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.ModelSource

/**
 * Keeps what is drawn in step with the level: each [Scenery]'s `ModelRenderer`, and the sun.
 *
 * ## Why presentation attaches the model
 *
 * A level holds a [Scenery] naming a [Prop], and never a model: a model is `udea-render`'s, loaded
 * from files on the desktop, and a headless server or a level file has no business holding one.
 * `ModelRenderSystem` draws entities that carry a `ModelRenderer`, so something has to give each
 * prop the renderer for its model, and it has to keep doing so - a level load, a scene swap or an
 * editor's edit can replace an entity, or change its prop, at any tick. So this looks every frame,
 * before the models are drawn: an entity with no renderer gets one, and one whose prop changed gets
 * the new model. `ModelRenderer` is presentation state, never snapshotted, saved or replicated, so
 * adding it changes nothing the simulation, a replay or a level can see.
 *
 * A frame in which nothing changed allocates nothing: the only writes are for an entity whose model
 * is missing or stale.
 *
 * ## The sun
 *
 * The level's [Sunlight] is copied into [light], which `ModelRenderSystem` reads each frame. With no
 * sun in the world, [light] keeps what it had.
 *
 * @param models the model each prop is drawn with. Asked for every prop every frame, so it is a
 *   lookup: the same object for the same prop, and nothing loaded or made here.
 */
public class ScenerySystem(
    private val models: (Prop) -> ModelSource,
    private val light: ModelLight,
) : RenderSystem {

    private var bound: Bound? = null

    override fun onBind(world: World, ctx: GameContext) {
        bound = Bound(world, world.family { all(Scenery) }, world.family { all(Sunlight) })
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        sync()
    }

    /** Brings every prop's model and the light up to date with the world. Render thread. */
    internal fun sync() {
        val bound = bound ?: return
        with(bound.world) {
            bound.scenery.forEach { entity ->
                val wanted = models(entity[Scenery].prop)
                val drawn = entity.getOrNull(ModelRenderer)
                when {
                    drawn == null -> entity.configure { it += ModelRenderer(wanted) }
                    drawn.model !== wanted -> drawn.model = wanted
                }
            }
            bound.suns.forEach { entity -> copy(entity[Sunlight]) }
        }
    }

    private fun copy(sun: Sunlight) {
        light.directionX = sun.directionX
        light.directionY = sun.directionY
        light.directionZ = sun.directionZ
        // `Rgba` is a packed value class: no allocation.
        light.color = Rgba.of(sun.red, sun.green, sun.blue)
        light.intensity = sun.intensity
        light.ambient = Rgba.of(sun.ambientRed, sun.ambientGreen, sun.ambientBlue)
    }

    override fun toString(): String = "ScenerySystem($light)"

    private class Bound(val world: World, val scenery: Family, val suns: Family)
}
