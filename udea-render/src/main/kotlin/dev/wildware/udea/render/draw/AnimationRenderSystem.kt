package dev.wildware.udea.render.draw

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderSystem

/**
 * Advances every playing [SpriteAnimation] and writes its current frame into the entity's
 * [SpriteRenderer].
 *
 * The port of `AnimationSystem` (`common/ecs/system/AnimationSystem.kt`), which advanced
 * animations with `gameScreen.delta` from inside the world tick. Two consequences, both fixed
 * by moving it here:
 *
 * - a headless server advanced playheads for sprites nobody would ever see, and did it at
 *   whatever rate the tick happened to run at;
 * - `AnimationSystem.onNotify` and `AnimationSetSystem.setAnimation` were reachable from
 *   simulation code, so which *picture* was showing could be branched on by a system that
 *   decides what happens. Choosing an animation is a presentation decision now: a simulation
 *   system emits a cue and something on this side reacts to it.
 *
 * It draws nothing itself, which is why it registers at `RenderPhase.World` *before*
 * [SpriteRenderSystem] rather than replacing it: one system owns the playhead, one owns the
 * batch.
 */
public class AnimationRenderSystem(
    /** Wall seconds per frame. A playhead is wall-timed; nothing simulated reads it. */
    private val frameTime: FrameTime,
) : RenderSystem {

    private var bound: Bound? = null

    /** Playheads advanced by the most recent frame. What `DrawSystemPortTest` counts. */
    public var advancedCount: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        bound = Bound(world, world.family { all(SpriteAnimation, SpriteRenderer) })
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val bound = this.bound ?: return
        advancedCount = 0
        val delta = frameTime.frameSeconds

        with(bound.world) {
            bound.animations.forEach { entity ->
                val state = entity[SpriteAnimation]
                val animation = state.animation ?: return@forEach

                if (state.playing) {
                    state.stateTime += delta
                    advancedCount++
                }
                // Written every frame, playing or not: a paused animation still has to show its
                // current frame, and a sprite whose region was only assigned while playing goes
                // blank the moment something pauses it.
                entity[SpriteRenderer].region = animation.getKeyFrame(state.stateTime)
            }
        }
    }

    private class Bound(val world: World, val animations: Family)
}
