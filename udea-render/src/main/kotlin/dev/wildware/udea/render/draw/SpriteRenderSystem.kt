package dev.wildware.udea.render.draw

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.interp.Pose

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
 */
public class SpriteRenderSystem(
    private val resources: RenderResources,
    private val camera: CameraRig,
    private val interpolator: Interpolator,
) : RenderSystem {

    private var bound: Bound? = null

    /** Reused: one pose object for the whole frame, whatever the entity count. */
    private val pose = Pose()

    /** Sprites drawn by the most recent frame. What `DrawSystemPortTest` counts. */
    public var drawnCount: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        val sprites = world.family { all(PhysicsBody, SpriteRenderer) }
        bound = Bound(
            world = world,
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
        if (bound.sprites.numEntities == 0) return

        bound.sprites.sort(bound.order)

        val batch = resources.batch
        batch.projectionMatrix = camera.camera.combined
        batch.begin()
        try {
            with(bound.world) {
                bound.sprites.forEach { entity -> draw(entity, alpha) }
            }
        } finally {
            // In a `finally` because a `Batch` left begun poisons every later pass in the
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

        batch.color = sprite.tint
        batch.draw(
            region,
            pose.x + sprite.offsetX - halfWidth,
            pose.y + sprite.offsetY - halfHeight,
            halfWidth,
            halfHeight,
            sprite.width,
            sprite.height,
            if (sprite.flipX) -1f else 1f,
            if (sprite.flipY) -1f else 1f,
            // The batch takes degrees; a body's angle is radians, like everything simulated.
            Math.toDegrees(pose.angle.toDouble()).toFloat(),
        )
        drawnCount++
    }

    /** Everything resolved at bind time, so nothing here is nullable on the drawing path. */
    private class Bound(
        val world: World,
        val sprites: Family,
        val order: Comparator<Entity>,
    )
}
