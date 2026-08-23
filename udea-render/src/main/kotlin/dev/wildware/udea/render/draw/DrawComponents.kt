package dev.wildware.udea.render.draw

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Animation
import com.badlogic.gdx.graphics.g2d.ParticleEffect
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

/**
 * Everything an entity needs in order to be *drawn*, and nothing it needs in order to be
 * *simulated*.
 *
 * ## Why these live in `udea-render` and not in `udea-core`
 *
 * Each one holds a GL object — a `TextureRegion`, an `Animation`, a `ParticleEffect` — and
 * `udea-core` is a headless kernel with no GL on its compile classpath (spec 4). Putting a
 * texture on a kernel component would fail `udeaVerifyHeadless` on the first build, and would
 * be wrong for a better reason than the gate: a dedicated server allocates none of this, and
 * a snapshot must not carry it. **None of these components is ever snapshotted or replicated.**
 *
 * The entity's *position* is not here. `PhysicsBody` carries `x`, `y` and `angle`, it is
 * simulation state, and every renderer reads it through
 * [dev.wildware.udea.render.interp.Interpolator] so the drawn pose matches the interpolated one
 * everywhere. The old `Transform` component held position, rotation and scale together, so a
 * scale change and a physics write-back touched the same object and replication could not tell
 * them apart.
 */

/**
 * A single texture region drawn at the entity's interpolated pose.
 *
 * Replaces `SpriteRenderer` + the `Sprite`/`TextureRegion` branch in `SpriteBatchSystem`
 * (`SpriteBatchSystem.kt:44`). That branch existed because a `Sprite` carries its own position,
 * rotation and scale, so the system had to decide per entity, inside the hot loop, whose
 * transform won. Here the entity's transform always wins and the branch has nothing to decide,
 * so it is gone rather than split in two.
 */
public class SpriteRenderer(
    /** What to draw, or `null` to draw nothing this frame. */
    public var region: TextureRegion? = null,
    /** World-space offset from the body's origin. */
    public var offsetX: Float = 0f,
    /** World-space offset from the body's origin. */
    public var offsetY: Float = 0f,
    /** Width in world units. */
    public var width: Float = 1f,
    /** Height in world units. */
    public var height: Float = 1f,
    /** Mirrors horizontally without a second texture. */
    public var flipX: Boolean = false,
    /** Mirrors vertically. */
    public var flipY: Boolean = false,
    /** Draw order within the sprite pass. Lower draws first, so higher is nearer the viewer. */
    public var order: Int = 0,
    /** Multiplied into the texture. Alpha here is a *source* alpha and does not reach a capture. */
    public var tint: Color = Color(Color.WHITE),
) : Component<SpriteRenderer> {

    override fun type(): ComponentType<SpriteRenderer> = SpriteRenderer

    override fun toString(): String = "SpriteRenderer(order=$order, ${width}x$height)"

    public companion object : ComponentType<SpriteRenderer>()
}

/**
 * A playing sprite animation, which writes its current frame into [SpriteRenderer].
 *
 * ## What it replaces, and the one thing that had to change
 *
 * `AnimationSystem` advanced every animation by `gameScreen.delta` from inside the world tick
 * (`AnimationSystem.kt:20`), and `AnimationSetSystem` chose which animation was playing from
 * inside the same tick. So a headless server advanced animation playheads nobody would see, and
 * — worse — `setAnimation` was reachable from simulation code, which made the *picture* part of
 * the state a simulation system could branch on.
 *
 * Here the playhead is wall-timed presentation state advanced by [AnimationRenderSystem], and
 * choosing an animation is a presentation-side call. A simulation system that wants a different
 * animation played emits a cue; it does not reach in here.
 *
 * [stateTime] is seconds because a `com.badlogic.gdx.graphics.g2d.Animation` is defined in
 * seconds. That is legitimate — this is `udea-render`, one of the two places seconds exist
 * (spec 5, "Time") — and it is exactly why the field is not in a snapshot: seconds do not
 * survive a rewind.
 */
public class SpriteAnimation(
    /** The animation to play, or `null` for none. */
    public var animation: Animation<TextureRegion>? = null,
    /** Seconds into [animation]. Wall time, never simulated time. */
    public var stateTime: Float = 0f,
    /** Advance [stateTime]; `false` freezes the current frame without unsetting the animation. */
    public var playing: Boolean = true,
) : Component<SpriteAnimation> {

    /** Restarts [animation] from its first frame. */
    public fun restart() {
        stateTime = 0f
    }

    override fun type(): ComponentType<SpriteAnimation> = SpriteAnimation

    override fun toString(): String = "SpriteAnimation(t=$stateTime, playing=$playing)"

    public companion object : ComponentType<SpriteAnimation>()
}

/**
 * Particle effects that follow the entity.
 *
 * `ParticleSystemSystem.kt:31` advanced and drew in the same call, and that is kept: a LibGDX
 * `ParticleEffect` has no separate update, and particles are presentation-only — they never
 * enter a snapshot, so nothing downstream can tell the difference between advancing them in the
 * tick and advancing them in the frame. The delta now comes from
 * [dev.wildware.udea.render.FrameTime] rather than `gameScreen.delta`.
 */
public class ParticleEffects(
    /** The effects to draw. Owned by whoever created them; this component does not dispose. */
    public val effects: MutableList<ParticleEffect> = ArrayList(),
) : Component<ParticleEffects> {

    override fun type(): ComponentType<ParticleEffects> = ParticleEffects

    override fun toString(): String = "ParticleEffects(${effects.size})"

    public companion object : ComponentType<ParticleEffects>()
}

/**
 * Short-lived developer text pinned to an entity.
 *
 * Replaces `Debug`. Two things from the original are deliberately gone:
 *
 * - the label was `"local: ${entity.id}v${entity.version}"` plus a `Networkable.remoteEntity`
 *   lookup for the remote pair (`DebugDrawSystem.kt:44-48`). Entity identity is `NetId` now
 *   (spec 5), which is the *same* value on every machine, so there is no pair to print and no
 *   second lookup to keep in step.
 * - messages expired against `gameScreen.time`, a wall clock read inside the tick. They expire
 *   against a [dev.wildware.udea.core.Tick] here, so a rewind takes the labels back with it
 *   instead of leaving sixty seconds of stale text on screen.
 */
public class DebugLabels(
    /** Lines drawn above the entity, oldest first. */
    public val messages: MutableList<DebugLabel> = ArrayList(),
) : Component<DebugLabels> {

    override fun type(): ComponentType<DebugLabels> = DebugLabels

    override fun toString(): String = "DebugLabels(${messages.size})"

    public companion object : ComponentType<DebugLabels>()
}

/** One line of debug text, and the tick it stops being drawn at. */
public class DebugLabel(
    public val text: String,
    /** Drawn while the clock is before this tick. */
    public val expiresAt: dev.wildware.udea.core.Tick,
) {
    override fun toString(): String = "DebugLabel('$text' until ${expiresAt.value})"
}
