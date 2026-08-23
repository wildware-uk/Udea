package dev.wildware.udea.render.camera

import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.interp.Pose
import kotlin.math.exp

/**
 * The world camera, and the only thing that moves it.
 *
 * ## What it replaces, and why that was broken
 *
 * `CameraTrackSystem` (`common/ecs/system/CameraTrackSystem.kt:15`) was a Fleks
 * `IteratingSystem` that also implemented `InputProcessor` and wrote `gameScreen.camera` from
 * inside the world tick. Three separate consequences:
 *
 * - **camera state was simulation state.** A headless server ticked a camera nobody would ever
 *   look through, and a snapshot either captured a viewport — which is meaningless on the other
 *   machine — or diverged from the world it was captured with.
 * - the camera moved at whatever rate the tick ran at, so smoothing was expressed in ticks and
 *   changed feel when the tick rate did.
 * - it needed `gameScreen`, so it could not exist without a window.
 *
 * Here the rig lives entirely on the presentation side: it **reads** the world and writes
 * nothing into it. `CameraRigTest` ticks a world with the rig present and absent and asserts an
 * identical `WorldHasher.hash`, which is what turns "camera is not simulation state" from a
 * design intention into a checked property.
 *
 * ## Smoothing is in wall seconds, and frame-rate independent
 *
 * Following is exponential toward the target with a **half-life**: after [followHalfLife]
 * seconds the camera has closed half the remaining distance, whatever the frame rate. The naive
 * `position += (target - position) * 0.1f` per frame is the version that makes the camera twice
 * as tight at 120Hz as at 60Hz, so a game tuned on one machine feels wrong on another.
 */
public class CameraRig(
    /** Resolves the followed [NetId] to an entity. */
    private val netIds: NetIdIndex,
    /** Interpolated poses, so the camera tracks the same position the sprite is drawn at. */
    private val interpolator: Interpolator,
    /** Wall seconds per frame; smoothing is a wall-time behaviour, never a tick one. */
    private val frameTime: FrameTime,
    /** Minimum world units kept visible on the shorter axis. */
    private val worldWidth: Float = DEFAULT_WORLD_WIDTH,
    /** Minimum world units kept visible on the taller axis. */
    private val worldHeight: Float = DEFAULT_WORLD_HEIGHT,
) : RenderSystem {

    /** The world camera. Handed to a batch's projection matrix; never written by a system. */
    public val camera: OrthographicCamera = OrthographicCamera()

    /**
     * `ExtendViewport`, so a wider window shows *more world* rather than a stretched one.
     *
     * The old `GameScreen` used the same type (`UdeaGameManager.kt:93`) and that part was
     * right; what was wrong was who owned it.
     */
    public val viewport: ExtendViewport = ExtendViewport(worldWidth, worldHeight, camera)

    /**
     * The entity to follow, or `null` to leave the camera where it is.
     *
     * A [NetId] rather than a Fleks `Entity` because the followed thing is "the local player",
     * which survives a rewind, a restore and a reconnect — and a raw `Entity` does not (spec 5,
     * "Entity identity").
     */
    public var target: NetId? = null

    /** World-space offset from the followed entity, for an over-the-shoulder framing. */
    public var offsetX: Float = 0f

    /** World-space offset from the followed entity. */
    public var offsetY: Float = 0f

    /**
     * Seconds for the camera to close half the distance to its target. `0f` follows exactly.
     */
    public var followHalfLife: Float = DEFAULT_HALF_LIFE
        set(value) {
            require(value >= 0f && value.isFinite()) {
                "followHalfLife must be a non-negative number of seconds, was $value"
            }
            field = value
        }

    /**
     * Keeps the visible area inside these world bounds, or `null` for an unbounded level.
     *
     * Clamping happens *after* smoothing, so the camera settles against an edge rather than
     * easing toward a point beyond it and never arriving.
     */
    public var bounds: CameraBounds? = null

    private var boundWorld: World? = null

    private val pose = Pose()

    /** The last size this rig configured its viewport for, so it reconfigures only on change. */
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0

    override fun onBind(world: World, ctx: GameContext) {
        boundWorld = world
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        advance(target, alpha)
        // Binds the GL viewport for everything drawn after this system in the frame. Split from
        // [advance] because it is the only line here that needs a driver: the following, the
        // smoothing and the clamping are arithmetic, and arithmetic that can only be checked
        // with a window open does not get checked.
        viewport.apply()
    }

    /**
     * Moves the camera one frame's worth toward its target, and updates it.
     *
     * Everything [render] does except binding the GL viewport.
     */
    internal fun advance(target: OffscreenTarget, alpha: Float) {
        val world = boundWorld ?: return
        fitTo(target)

        val followed = this.target?.let(netIds::resolveOrNull)
        if (followed != null && interpolator.interpolate(world, followed, alpha, pose)) {
            val desiredX = pose.x + offsetX
            val desiredY = pose.y + offsetY
            val t = smoothingFactor(frameTime.frameSeconds)
            camera.position.x += (desiredX - camera.position.x) * t
            camera.position.y += (desiredY - camera.position.y) * t
        }

        bounds?.clamp(camera, viewport)
        camera.update()
    }

    /**
     * Places the camera exactly on its target, with no easing.
     *
     * For the frames where easing would be wrong: the first frame of a scene, and the frame
     * after a restore. Both are cases where the camera's previous position describes a world
     * that is gone, and easing from it drags the view across the level while the agent
     * screenshots it.
     */
    public fun snapToTarget() {
        val world = boundWorld ?: return
        val followed = target?.let(netIds::resolveOrNull) ?: return
        if (!interpolator.interpolate(world, followed, 1f, pose)) return
        camera.position.x = pose.x + offsetX
        camera.position.y = pose.y + offsetY
        bounds?.clamp(camera, viewport)
        camera.update()
    }

    /**
     * Sizes the viewport to the target it is drawing into.
     *
     * Taken from the [OffscreenTarget] rather than from the window, because that is the surface
     * this rig actually draws on: it is framebuffer-sized and does not move when a human drags
     * a window edge, which is what keeps two captures of the same tick comparable. The window's
     * own size reaches the presentation side through
     * [dev.wildware.udea.render.Resizable], which is the overlay's concern.
     */
    private fun fitTo(target: OffscreenTarget) {
        if (target.width == viewportWidth && target.height == viewportHeight) return
        viewportWidth = target.width
        viewportHeight = target.height
        // centerCamera = false: the camera is positioned by following, and recentring it here
        // would fight that every time the target size changed.
        viewport.update(target.width, target.height, false)
    }

    /**
     * Fraction of the remaining distance to close this frame.
     *
     * `1 - 2^(-dt / halfLife)`, which is the frame-rate-independent form: halving the frame
     * time halves the step, so two frames of a 120Hz display move the camera exactly as far as
     * one frame of a 60Hz one. A fixed per-frame fraction does not, and is why cameras tuned
     * on one machine feel sluggish on another.
     */
    private fun smoothingFactor(dtSeconds: Float): Float {
        if (followHalfLife <= 0f || dtSeconds <= 0f) return 1f
        return 1f - exp(-LN_2 * dtSeconds / followHalfLife)
    }

    private companion object {
        const val DEFAULT_WORLD_WIDTH: Float = 32f
        const val DEFAULT_WORLD_HEIGHT: Float = 18f

        /** A tenth of a second: tight enough to feel attached, loose enough to absorb jitter. */
        const val DEFAULT_HALF_LIFE: Float = 0.1f

        val LN_2: Float = kotlin.math.ln(2f)
    }
}
