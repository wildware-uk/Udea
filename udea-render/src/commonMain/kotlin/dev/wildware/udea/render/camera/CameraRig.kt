package dev.wildware.udea.render.camera

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.draw.Projection2D
import dev.wildware.udea.render.interp.Pose
import dev.wildware.udea.render.interp.PoseSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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
    /**
     * Where a followed entity is, so the camera tracks the same position the sprite is drawn at.
     *
     * A [PoseSource] and not an `Interpolator`, and the difference is the whole of what made
     * `render.follow_entity` a lie in `moba`: an `Interpolator` reads `PhysicsBody`, a `moba`
     * unit carries `Position`, so the rig resolved the id, found no pose, moved nothing and the
     * tool answered `{"following": 0}` regardless. A game hands over the reader for its own
     * spatial component; `Interpolator` is still the reader for a physics body, and
     * [followability] refuses a follow this source cannot serve instead of accepting it.
     */
    private val poses: PoseSource,
    /** Wall seconds per frame; smoothing is a wall-time behaviour, never a tick one. */
    private val frameTime: FrameTime,
    /** Minimum world units kept visible on the shorter axis. */
    private val worldWidth: Float = DEFAULT_WORLD_WIDTH,
    /** Minimum world units kept visible on the taller axis. */
    private val worldHeight: Float = DEFAULT_WORLD_HEIGHT,
) : RenderSystem {

    /** The world camera. Moved by this rig and by nothing else. */
    public val camera: Camera2D = Camera2D()

    /**
     * [ExtendViewport], so a wider window shows *more world* rather than a stretched one.
     *
     * The old `GameScreen` used LibGDX's type of the same name (`UdeaGameManager.kt:93`) and that
     * part was right; what was wrong was who owned it.
     */
    public val viewport: ExtendViewport = ExtendViewport(worldWidth, worldHeight)

    /**
     * World units to target pixels, through [camera] and [viewport], as of the last frame this rig
     * advanced. What a world renderer hands to `SpriteBatch2D.begin`.
     */
    public val projection: Projection2D = Projection2D()

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

    /**
     * A camera placement asked for by another thread, applied at the top of the next frame.
     *
     * The agent's `render.set_camera` arrives on the simulation thread, inside a `SimBarrier`
     * drain; on an `Offscreen` or `Windowed` host that is also the render thread, but a host
     * whose agent loop runs on a thread of its own is a legitimate arrangement and writing
     * [camera] from it would tear the projection a frame is being drawn with. A slot under
     * [requests] consumed in [advance] costs one uncontended lock per frame and makes the question
     * moot: the camera only ever moves at a frame boundary, whoever asked.
     */
    private var pendingLook: LookAt? = null

    /** A follow target asked for by another thread. See [pendingLook]; `Follow` wraps a null id. */
    private var pendingFollow: Follow? = null

    /** Guards [pendingLook] and [pendingFollow]; atomicfu's, so it exists in common code. */
    private val requests = SynchronizedObject()

    private val pose = Pose()

    /**
     * Scratch pose for [followability], kept apart from [pose] on purpose.
     *
     * [pose] is the render thread's, written every frame inside [advance]. A probe that shared
     * it would overwrite the position a frame is being drawn from, which is a one-frame camera
     * jump that only shows up when somebody asks whether an entity is followable — the worst
     * kind of coupling to debug from a screenshot.
     */
    private val probe = Pose()

    /** The last size this rig configured its viewport for, so it reconfigures only on change. */
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0

    override fun onBind(world: World, ctx: GameContext) {
        boundWorld = world
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        // An editor's Scene view is being drawn: [projection] is that view's until [leaveView], and
        // the game's camera neither follows nor eases for a frame it is not drawing.
        if (inView) return
        advance(target, alpha)
    }

    /** True between [enterView] and [leaveView]. */
    private var inView: Boolean = false

    /** The game's projection, kept while a view's is in [projection]. */
    private val gameProjection = Projection2D()

    /**
     * Puts [view] in [projection] until [leaveView], for the pipeline's second run of the render
     * systems through an editor's Scene view (issue #234). Every system that draws through this rig
     * reads [projection], so each draws the world through the editor's camera without knowing a view
     * exists. Render thread, called by the pipeline between systems and never by one.
     */
    internal fun enterView(view: Projection2D) {
        check(!inView) { "$this is already drawing a view" }
        gameProjection.set(projection)
        projection.set(view)
        inView = true
    }

    /** Puts the game's projection back. See [enterView]. */
    internal fun leaveView() {
        if (!inView) return
        projection.set(gameProjection)
        inView = false
    }

    /**
     * Moves the camera one frame's worth toward its target, and republishes [projection].
     *
     * All of [render]: on Kool nothing here touches a driver, since the projection is arithmetic a
     * renderer hands to its batch rather than a viewport bound on the context.
     */
    internal fun advance(target: OffscreenTarget, alpha: Float) {
        // Before the bound-world check, and updated even without one: `render.set_camera` is a
        // presentation command and must land whether or not this rig has been bound to a world.
        // A placement that silently did nothing until a world arrived would read to an agent as
        // a camera that ignores it.
        if (applyRequests()) {
            fitTo(target)
            bounds?.clamp(camera, viewport)
            viewport.project(camera, projection)
        }
        val world = boundWorld ?: return
        fitTo(target)

        // Spelled out rather than `target?.let(netIds::resolveOrNull)`: a bound callable
        // reference is an object, Kotlin does not cache one, and this runs every frame.
        val followedId = this.target
        val followed = if (followedId == null) null else netIds.resolveOrNull(followedId)
        if (followed != null && poses.poseOf(world, followed, alpha, pose)) {
            val desiredX = pose.x + offsetX
            val desiredY = pose.y + offsetY
            val t = halfLifeStep(frameTime.frameSeconds, followHalfLife)
            camera.position.x += (desiredX - camera.position.x) * t
            camera.position.y += (desiredY - camera.position.y) * t
        }

        bounds?.clamp(camera, viewport)
        viewport.project(camera, projection)
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
        val followedId = target ?: return
        val followed = netIds.resolveOrNull(followedId) ?: return
        if (!poses.poseOf(world, followed, 1f, pose)) return
        camera.position.x = pose.x + offsetX
        camera.position.y = pose.y + offsetY
        bounds?.clamp(camera, viewport)
        viewport.project(camera, projection)
    }

    /**
     * Asks for the camera to be placed at ([x], [y]) with this [zoom], from any thread.
     *
     * Applied at the top of the next frame, and it **stops following**: a placement that left
     * the follow target alone would be undone by the same frame that applied it, and an agent
     * that asked to look at a corner of the map would see the camera snap back to the unit it
     * was tracking. `render.follow_entity` is how following is resumed.
     *
     * @param zoom orthographic zoom, larger showing more world. Must be positive and finite:
     *   `camera.zoom = 0` collapses the projection and draws a frame of nothing, which
     *   an agent would read as "the game went black".
     */
    public fun requestLookAt(x: Float, y: Float, zoom: Float) {
        require(x.isFinite() && y.isFinite()) { "camera position must be finite, was ($x, $y)" }
        require(zoom > 0f && zoom.isFinite()) { "camera zoom must be a positive number, was $zoom" }
        synchronized(requests) { pendingLook = LookAt(x, y, zoom) }
    }

    /**
     * Asks the rig to follow [netId], or to stop following when it is `null`, from any thread.
     *
     * Stopping leaves the camera exactly where it is rather than resetting it: an agent that
     * stops following has usually just found the thing it wants to look at.
     */
    public fun requestFollow(netId: NetId?) {
        synchronized(requests) { pendingFollow = Follow(netId) }
    }

    /**
     * Whether following [netId] would actually move the camera, and if not, why not.
     *
     * ## Why this exists
     *
     * [requestFollow] accepts anything: it records the request and returns, and the
     * frame that consumes it resolves the id and asks the [PoseSource] for a pose. When either
     * step comes back empty the camera simply does not move, and *nothing says so*. `moba` had
     * that written down as a known lie — its units carry `Position` and no `PhysicsBody`, so
     * `render.follow_entity` answered `ok` and the camera stayed exactly where it was. An agent
     * that then screenshots and sees the wrong part of the map has no way to attribute it.
     *
     * This is the same two steps [advance] takes, run once, before the answer is given, so the
     * caller can report the reason instead of a silence.
     *
     * ## Threading
     *
     * **Simulation thread only**, unlike the rest of this class: it resolves through
     * [NetIdIndex] and reads components off the world, and both belong to the thread that ticks
     * the simulation. Every agent tool call satisfies that — a tool runs inside a `SimBarrier`
     * drain — and a caller that is somewhere else must ask [requestFollow] and live with the
     * silence, which is the trade this method exists to let a host avoid.
     */
    public fun followability(netId: NetId): CameraOutcome {
        val world = boundWorld ?: return CameraOutcome.CAMERA_UNBOUND
        val entity = netIds.resolveOrNull(netId) ?: return CameraOutcome.UNKNOWN_ENTITY
        // alpha = 1: the question is whether a pose exists at all, and `interpolate` answers
        // that with its return value rather than with what it wrote.
        if (!poses.poseOf(world, entity, 1f, probe)) return CameraOutcome.UNFOLLOWABLE
        return CameraOutcome.APPLIED
    }

    /**
     * Consumes whatever another thread asked for. Render thread only.
     *
     * @return true if anything was applied, so the caller knows to update the camera.
     */
    private fun applyRequests(): Boolean {
        var applied = false
        var follow: Follow? = null
        var look: LookAt? = null
        synchronized(requests) {
            follow = pendingFollow
            look = pendingLook
            pendingFollow = null
            pendingLook = null
        }
        if (follow != null) {
            target = follow.netId
            applied = true
        }
        if (look != null) {
            // Placing the camera by hand and following are two answers to "where does the camera
            // go", and the frame can only have one. See `requestLookAt`.
            target = null
            camera.position.x = look.x
            camera.position.y = look.y
            camera.zoom = look.zoom
            applied = true
        }
        return applied
    }

    /** One queued placement. See [pendingLook]. */
    private class LookAt(val x: Float, val y: Float, val zoom: Float)

    /** One queued follow request; [netId] is null for "stop following". See [pendingFollow]. */
    private class Follow(val netId: NetId?)

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
        // The camera is not recentred: it is positioned by following, and recentring it here
        // would fight that every time the target size changed.
        viewport.update(target.width, target.height)
    }

    private companion object {
        const val DEFAULT_WORLD_WIDTH: Float = 32f
        const val DEFAULT_WORLD_HEIGHT: Float = 18f

        /** A tenth of a second: tight enough to feel attached, loose enough to absorb jitter. */
        const val DEFAULT_HALF_LIFE: Float = 0.1f
    }
}
