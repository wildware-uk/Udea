package dev.wildware.udea.render.draw

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.interp.Pose
import dev.wildware.udea.render.view.PickBounds
import dev.wildware.udea.render.view.PickSink
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws every [SpriteRenderer] at its entity's interpolated pose, back to front.
 *
 * The port of `SpriteBatchSystem` (`common/ecs/system/SpriteBatchSystem.kt`). Four things
 * changed, and each of them was a defect rather than a preference:
 *
 * | Old | Here |
 * |---|---|
 * | a Fleks `IteratingSystem`, so `world.update(dt)` drew | a [RenderSystem], outside the world's system list (spec 3.3) |
 * | camera from the `gameScreen` global (`:26`) | a [CameraRig] passed to the constructor |
 * | a `Sprite`/`TextureRegion` type check per entity per frame (`:44`) | one draw path; the entity's transform always wins |
 * | drawn at the last simulated position | drawn at the interpolated one, so 60Hz does not judder |
 *
 * ## Ordering, without a per-frame array
 *
 * `SpriteBatchSystem` passed a `compareEntity` comparator to Fleks, which sorted the family in
 * place — that part was right and is kept. [Family.sort] sorts the family's own entity bag with
 * no copy, so a frame allocates neither an array nor a comparator: both are made once, at bind.
 * The naive port — collect into a `List` and `sortedBy` it — allocates two objects per frame per
 * pass, which `RenderAllocationTest` catches: that exact mutation — collect into an
 * `ArrayList`, `sortWith` it — takes both of its assertions red.
 *
 * ## Pickable
 *
 * It reports each sprite's rectangle to an editor ([PickBounds], issue #235): the rectangle it
 * draws, turned by the body's angle, at the pose of the last frame it drew, so what a click hits is
 * what the person sees.
 */
public class SpriteRenderSystem(
    private val resources: RenderResources,
    private val camera: CameraRig,
    private val interpolator: Interpolator,
) : RenderSystem, PickBounds {

    private var bound: Bound? = null

    /** Reused: one pose object for the whole frame, whatever the entity count. */
    private val pose = Pose()

    /** The alpha the most recent frame drew at: where [reportPickBounds] places each sprite. */
    private var lastAlpha = 0f

    /** Sprites drawn by the most recent frame. What `DrawSystemPortTest` counts. */
    public var drawnCount: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        val sprites = world.family { all(PhysicsBody, SpriteRenderer) }
        bound = Bound(
            world = world,
            ctx = ctx,
            sprites = sprites,
            // Built once. A comparator allocated per frame is per-frame garbage, and one
            // allocated per *comparison* — which is what a lambda capturing the entity would
            // be — is garbage proportional to n log n.
            order = Comparator { left: Entity, right: Entity ->
                with(world) { left[SpriteRenderer].order.compareTo(right[SpriteRenderer].order) }
            },
        )
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val bound = this.bound ?: return
        drawnCount = 0
        lastAlpha = alpha
        if (bound.sprites.numEntities == 0) return

        bound.sprites.sort(bound.order)

        val batch = resources.batch
        batch.begin(camera.projection)
        try {
            with(bound.world) {
                bound.sprites.forEach { entity -> draw(entity, alpha) }
            }
        } finally {
            // In a `finally` because a batch left begun poisons every later pass in the
            // frame with a "batch already begun" failure that names the wrong system.
            batch.end()
        }
    }

    private fun World.draw(entity: Entity, alpha: Float) {
        val sprite = entity[SpriteRenderer]
        val region = sprite.region ?: return
        if (!interpolator.interpolate(this, entity, alpha, pose)) return

        val batch = resources.batch
        val halfWidth = sprite.width / 2f
        val halfHeight = sprite.height / 2f

        batch.draw(
            region,
            x = pose.x + sprite.offsetX - halfWidth,
            y = pose.y + sprite.offsetY - halfHeight,
            width = sprite.width,
            height = sprite.height,
            tint = sprite.tint,
            // The batch takes degrees; a body's angle is radians, like everything simulated.
            rotationDegrees = pose.angle * RADIANS_TO_DEGREES,
            originX = halfWidth,
            originY = halfHeight,
            flipX = sprite.flipX,
            flipY = sprite.flipY,
        )
        drawnCount++
    }

    /**
     * Each sprite's drawn rectangle, back to front: the axis-aligned box round the rectangle
     * [render] draws, turned about its centre by the body's angle.
     */
    override fun reportPickBounds(out: PickSink) {
        val bound = this.bound ?: return
        if (bound.sprites.numEntities == 0) return
        // Resolved here rather than at bind: a pipeline built for an ordering test binds a context
        // with no core module, and never picks.
        val netIds = bound.ctx[CoreModule.NET_IDS]
        bound.sprites.sort(bound.order)
        with(bound.world) {
            bound.sprites.forEach { entity ->
                val id = netIds.netIdOf(entity)
                if (!id.isNone) report(entity, id, out)
            }
        }
    }

    private fun World.report(entity: Entity, id: NetId, out: PickSink) {
        val sprite = entity[SpriteRenderer]
        if (sprite.region == null) return
        if (!interpolator.interpolate(this, entity, lastAlpha, pose)) return
        val centreX = pose.x + sprite.offsetX
        val centreY = pose.y + sprite.offsetY
        val halfWidth = sprite.width / 2f
        val halfHeight = sprite.height / 2f
        val cosine = abs(cos(pose.angle))
        val sine = abs(sin(pose.angle))
        val reachX = halfWidth * cosine + halfHeight * sine
        val reachY = halfWidth * sine + halfHeight * cosine
        out.rect(id, centreX - reachX, centreY - reachY, centreX + reachX, centreY + reachY)
    }

    private companion object {
        const val RADIANS_TO_DEGREES: Float = (180.0 / kotlin.math.PI).toFloat()
    }

    /** Everything resolved at bind time, so nothing here is nullable on the drawing path. */
    private class Bound(
        val world: World,
        val ctx: GameContext,
        val sprites: Family,
        val order: Comparator<Entity>,
    )
}
