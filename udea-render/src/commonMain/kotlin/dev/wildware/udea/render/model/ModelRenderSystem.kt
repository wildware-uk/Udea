package dev.wildware.udea.render.model

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.interp.Pose
import dev.wildware.udea.render.interp.PoseSource
import dev.wildware.udea.render.view.WorldViewport

/**
 * Draws every entity with a [ModelRenderer] - a built-in mesh in its material, or an
 * [ImportedModel] in the materials of its own file - lit by [light], seen from [camera].
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
 * ## How an imported model is posed
 *
 * An [ImportedModel] with a skin and clips is posed from the entity's `Animator` (issue #242): the
 * clip, where in it, and the crossfade from the clip before, read at the simulation's current tick
 * plus the interpolation alpha - see [ClipPose], which is where a clip's time becomes seconds - and
 * skinned on the GPU. An entity with no `Animator` draws the bind pose; a model with no skin
 * ignores its `Animator`. The pose depends on nothing but the tick, the alpha and the `Animator`,
 * so a replay draws the same frames at the same ticks.
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

    /** Reused: each entity's clips this frame, one for the whole frame. */
    private val clips = ClipPose()

    /** Models drawn by the most recent frame. What `GlModelRenderTest` counts. */
    internal var drawnCount: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        bound = Bound(world, world.family { all(ModelRenderer) }, ctx.clock, ctx[CoreModule.NET_IDS])
    }

    /**
     * Writes into [out] the joints of [entity]'s skinned model as [view] shows it this frame - in its
     * scrub preview's pose, when [view] is previewing [entity] ([ModelPreview.Pose]) - or as the
     * capturable frame shows it when [view] is `null` (issue #243). What the editor's bone overlay
     * draws; see `ModelStage.skeleton` for where a joint is.
     *
     * Render thread, after this frame's models are drawn: from a `GizmoLayer`, which a view draws
     * after its world.
     *
     * @return false, with [out] empty, when [entity] is not in the world, was not drawn this frame,
     *   or is drawn with a model that has no skin.
     */
    public fun skeletonOf(entity: NetId, view: WorldViewport?, out: ModelSkeleton): Boolean {
        val bound = this.bound
        val live = bound?.netIds?.resolveOrNull(entity)
        if (live == null) {
            out.clear()
            return false
        }
        return stage.skeleton(live.id, view, out)
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val bound = this.bound ?: return
        // An editor's Scene view (issue #234): the models this frame are already placed, by the run
        // for the capturable frame, and the view sees them through its own camera and pass.
        val view = resources.viewing.current
        val image = if (view != null) {
            stage.imageFor(view, camera, previewedEntity(bound, view))
        } else {
            drawnCount = 0
            stage.fit(target.width, target.height)
            stage.begin(camera, light)
            val now = bound.clock.tick
            with(bound.world) {
                bound.models.forEach { entity -> draw(entity, now, alpha) }
            }
            stage.image
        }

        val batch = resources.batch
        batch.beginPixels()
        try {
            batch.draw(image, 0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.WHITE)
        } finally {
            batch.end()
        }
    }

    /** The Fleks id of the entity [view]'s scrub preview is of, or -1 for none (issue #243). */
    private fun previewedEntity(bound: Bound, view: WorldViewport): Int {
        val preview = view.modelPreview as? ModelPreview.Pose ?: return NO_PREVIEW
        return bound.netIds.resolveOrNull(preview.entity)?.id ?: NO_PREVIEW
    }

    private fun World.draw(entity: Entity, now: Tick, alpha: Float) {
        val model = entity[ModelRenderer].model
        val transform = entity.getOrNull(Transform3D)
        if (transform != null) {
            stage.add(
                model,
                transform.x, transform.y, transform.z,
                transform.rotationX, transform.rotationY, transform.rotationZ,
                transform.scaleX, transform.scaleY, transform.scaleZ,
                clips.set(entity.getOrNull(Animator), now, alpha),
                entity.id,
            )
        } else {
            val lift = lift ?: return
            if (!lift.poseOf(this, entity, alpha, pose)) return
            stage.add(
                model, pose.x, pose.y, 0f, 0f, 0f, pose.angle, 1f, 1f, 1f,
                clips.set(entity.getOrNull(Animator), now, alpha),
                entity.id,
            )
        }
        drawnCount++
    }

    /** Everything resolved at bind time. */
    private class Bound(val world: World, val models: Family, val clock: SimClock, val netIds: NetIdIndex)

    private companion object {
        /** No entity is previewed: what `ModelStage.imageFor` matches against no node. */
        const val NO_PREVIEW = -1
    }
}
