package dev.wildware.udea.render.model

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.interp.Pose
import dev.wildware.udea.render.interp.PoseSource

/**
 * Draws every entity with a [ModelRenderer]: its mesh, in its material, lit by [light], seen from
 * [camera].
 *
 * A [RenderSystem], not a Fleks system, like every drawing system here: `world.update` stays pure
 * simulation. The Kool half - the 3D pass, its light and shadow map, one PBR shader per material -
 * is [ModelStage]'s, and none of it is visible from here.
 *
 * ## Where an entity is drawn
 *
 * - With a `Transform3D`, there: position, rotation and scale, as they stand. A `Transform3D` is
 *   not interpolated, because nothing in the engine simulates one yet.
 * - Without one, from [lift], if it was given one: a 2D pose `(x, y, angle)` is drawn at
 *   `(x, y, 0)` - on the ground plane - turned `angle` about Z, at scale 1. That is how a 2D game
 *   shows a 3D model without carrying 3D data: pass the same `PoseSource` its camera follows
 *   (`Interpolator` for `PhysicsBody`, or the game's own).
 * - With neither, it is not drawn.
 *
 * ## How it reaches the capture
 *
 * The 3D world is drawn into a pass of its own - it needs a depth buffer and a perspective camera,
 * which the 2D pass has neither of - and [render] draws that pass's image into the capturable
 * batch, full frame, at this system's place in the phase order. So a background drawn before it
 * shows behind the models, sprites drawn after it are on top, and a capture holds all of it.
 *
 * @param lift where an entity with no `Transform3D` stands, or `null` to draw only entities that
 *   have one.
 * @throws IllegalStateException from the constructor if [resources] has no Kool scene behind it -
 *   a pipeline built for an ordering test, which cannot draw anything.
 */
public class ModelRenderSystem(
    private val resources: RenderResources,
    private val camera: ModelCamera,
    private val light: ModelLight,
    private val lift: PoseSource? = null,
) : RenderSystem {

    private val stage: ModelStage = resources.own(
        ModelStage(
            checkNotNull(resources.passes) {
                "ModelRenderSystem needs a Kool surface to draw into; this pipeline has none"
            },
            resources.offscreen.width,
            resources.offscreen.height,
        ),
    )

    private var bound: Bound? = null

    /** Reused: the pose [lift] writes into, one for the whole frame. */
    private val pose = Pose()

    /** Models drawn by the most recent frame. What `GlModelRenderTest` counts. */
    internal var drawnCount: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        bound = Bound(world, world.family { all(ModelRenderer) })
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val bound = this.bound ?: return
        drawnCount = 0
        stage.begin(camera, light)
        with(bound.world) {
            bound.models.forEach { entity -> draw(entity, alpha) }
        }

        val batch = resources.batch
        batch.beginPixels()
        try {
            batch.draw(stage.image, 0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.WHITE)
        } finally {
            batch.end()
        }
    }

    private fun World.draw(entity: Entity, alpha: Float) {
        val model = entity[ModelRenderer]
        val transform = entity.getOrNull(Transform3D)
        if (transform != null) {
            stage.add(
                model.mesh, model.material,
                transform.x, transform.y, transform.z,
                transform.rotationX, transform.rotationY, transform.rotationZ,
                transform.scaleX, transform.scaleY, transform.scaleZ,
            )
        } else {
            val lift = lift ?: return
            if (!lift.poseOf(this, entity, alpha, pose)) return
            stage.add(model.mesh, model.material, pose.x, pose.y, 0f, 0f, 0f, pose.angle, 1f, 1f, 1f)
        }
        drawnCount++
    }

    /** Everything resolved at bind time. */
    private class Bound(val world: World, val models: Family)
}
