package dev.wildware.udea.render.model

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.interp.PoseSource
import dev.wildware.udea.render.view.PickBounds
import dev.wildware.udea.render.view.PickSink
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
 * - With a `Transform3D`, there: position and heading between where the last two ticks left it,
 *   at the render alpha, when the world runs `RenderModule` (issue #246); pitch, roll and scale as
 *   they stand. [ModelPlacer] has the rules.
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
 * ## Pickable
 *
 * It reports each model's world box to an editor ([PickBounds], issue #235), placed by the same
 * transform it is drawn with: see [ModelBounds] for what the box covers.
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
) : RenderSystem, PickBounds {

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

    /** Reused: each entity's clips this frame, one for the whole frame. */
    private val clips = ClipPose()

    /** Where the entity being drawn or reported stands: interpolated, or [lift]ed. */
    private val placer = ModelPlacer(lift)

    /** Each model's world box, for [reportPickBounds]. */
    private val bounds = ModelBounds()

    /** The alpha the most recent frame drew at: where [reportPickBounds] places each model. */
    private var lastAlpha = 0f

    /** Models drawn by the most recent frame. What `GlModelRenderTest` counts. */
    internal var drawnCount: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        bound = Bound(world, ctx, world.family { all(ModelRenderer) }, ctx.clock)
        placer.bind(world, ctx.clock)
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
        val live = bound?.ctx?.get(CoreModule.NET_IDS)?.resolveOrNull(entity)
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
            lastAlpha = alpha
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
        return bound.ctx[CoreModule.NET_IDS].resolveOrNull(preview.entity)?.id ?: NO_PREVIEW
    }

    private fun World.draw(entity: Entity, now: Tick, alpha: Float) {
        if (!placer.place(this, entity, alpha)) return
        val at = placer.placed
        stage.add(
            entity[ModelRenderer].model,
            at.x, at.y, at.z, at.rotationX, at.rotationY, at.rotationZ, at.scaleX, at.scaleY, at.scaleZ,
            clips.set(entity.getOrNull(Animator), now, alpha),
            entity.id,
        )
        drawnCount++
    }

    /** Each model's world box, as it was placed by the most recent frame. */
    override fun reportPickBounds(out: PickSink) {
        val bound = this.bound ?: return
        // Resolved here rather than at bind: a pipeline built for an ordering test binds a context
        // with no core module, and never picks.
        val netIds = bound.ctx[CoreModule.NET_IDS]
        with(bound.world) {
            bound.models.forEach { entity ->
                val id = netIds.netIdOf(entity)
                if (!id.isNone && placer.place(bound.world, entity, lastAlpha)) {
                    val at = placer.placed
                    bounds.report(
                        id, entity[ModelRenderer].model,
                        at.x, at.y, at.z, at.rotationX, at.rotationY, at.rotationZ, at.scaleX, at.scaleY, at.scaleZ,
                        out,
                    )
                }
            }
        }
    }

    /** Everything resolved at bind time. */
    private class Bound(val world: World, val ctx: GameContext, val models: Family, val clock: SimClock)

    private companion object {
        /** No entity is previewed: what `ModelStage.imageFor` matches against no node. */
        const val NO_PREVIEW = -1
    }
}
