package dev.wildware.udea.render.draw

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.interp.Pose

/**
 * Moves every [ParticleEffects] to its entity's interpolated pose, advances it, and draws it.
 *
 * The port of `ParticleSystemSystem` (`common/ecs/system/ParticleSystemSystem.kt`).
 * `ParticleSystemSystem.kt:31` advanced and drew in one call, and that is kept deliberately:
 * `ParticleEffect.draw(batch, delta)` is LibGDX's only combined update-and-draw, particles are
 * presentation-only and never enter a snapshot, so nothing downstream can observe the
 * difference. What changed is where the delta comes from — [FrameTime] instead of the
 * `gameScreen` global — and that the effect follows the *interpolated* pose, so a trail does
 * not lag the sprite it is trailing by up to one tick.
 */
public class ParticleRenderSystem(
    private val resources: RenderResources,
    private val camera: CameraRig,
    private val interpolator: Interpolator,
    private val frameTime: FrameTime,
) : RenderSystem {

    private var bound: Bound? = null

    private val pose = Pose()

    /** Effects drawn by the most recent frame. What `DrawSystemPortTest` counts. */
    public var drawnCount: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        bound = Bound(world, world.family { all(PhysicsBody, ParticleEffects) })
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val bound = this.bound ?: return
        drawnCount = 0
        if (bound.effects.numEntities == 0) return

        val batch = resources.batch
        val delta = frameTime.frameSeconds
        batch.projectionMatrix = camera.camera.combined
        batch.begin()
        try {
            val world = bound.world
            with(world) {
                bound.effects.forEach { entity ->
                    if (!interpolator.interpolate(world, entity, alpha, pose)) return@forEach
                    val effects = entity[ParticleEffects].effects
                    for (index in effects.indices) {
                        val effect = effects[index]
                        effect.setPosition(pose.x, pose.y)
                        effect.draw(batch, delta)
                        drawnCount++
                    }
                }
            }
        } finally {
            batch.end()
        }
    }

    private class Bound(val world: World, val effects: Family)
}
