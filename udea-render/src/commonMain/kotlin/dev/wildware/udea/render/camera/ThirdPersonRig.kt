package dev.wildware.udea.render.camera

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.input.PointerMotion
import dev.wildware.udea.render.input.PointerState
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelPlacer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A third-person camera for a 3D world (issue #248): behind and above the entity it follows, at a
 * fixed distance, turned by the mouse, and eased after its target in wall seconds.
 *
 * [CameraRig] is its 2D counterpart, and the two share the rule that matters most: this **reads the
 * world and writes nothing into it**. It is a [RenderSystem], never a Fleks system, so a dedicated
 * server has no camera at all and a snapshot carries nothing about where somebody was looking.
 * `ThirdPersonRigTest` hashes a world ticked with the rig present and absent and asserts the two
 * agree.
 *
 * ## Wiring
 *
 * The rig moves a [ModelCamera]; a `ModelRenderSystem` handed the same one draws through it. The rig
 * goes in [dev.wildware.udea.render.RenderPhase.PreRender], so every frame's camera is placed before
 * any model is drawn with it:
 *
 * ```kotlin
 * val camera = ModelCamera()
 * lateinit var rig: ThirdPersonRig
 * registry.register(RenderPhase.PreRender, { r -> ThirdPersonRig(r, netIds, registry.frameTime, camera).also { rig = it } })
 * registry.register(RenderPhase.World, { r -> ModelRenderSystem(r, camera, light) })
 * // Once the backend is up, on the render thread:
 * val pointer = KoolPointer(ui)
 * rig.motion = pointer
 * rig.buttons = pointer
 * rig.target = playerNetId
 * ```
 *
 * ## The frame
 *
 * Z is up and the ground is the XY plane, as `Transform3D` has it. [yawDegrees] is the direction the
 * camera faces across the ground, counter-clockwise from +X; [pitchDegrees] is how far it looks down.
 * The camera looks at its **focus** - the followed entity's `Transform3D` position raised by
 * [focusHeight] - from [distance] away, so the focus is the middle of the picture. The focus eases
 * after the target with [followHalfLife]; the turn does not ease, because a view that lags the hand
 * turning it reads as a slow mouse.
 *
 * Where the target is: its `Transform3D` between where the last two ticks left it, at the render
 * alpha (issue #246), read through the same `ModelPlacer` `ModelRenderSystem` draws with, so the
 * model and the middle of the picture cannot drift apart. [targetPosition] is the one place that
 * reads it.
 *
 * ## Facing, for camera-relative movement
 *
 * [forwardX], [forwardY], [rightX] and [rightY] are the camera's facing on the ground as plain
 * floats, so a game can make WASD walk where the camera looks without naming a Kool type. Read them
 * where the game **samples input** - in its `IntentSource`, turning the stick into world axes before
 * the intent is written - never in a simulation system. The intent is what is recorded, replayed and
 * sent to a server; a simulation system that read the camera would be reading presentation state that
 * a server and a replay do not have.
 *
 * ## Threads
 *
 * The render thread, like every [RenderSystem]. On every host this engine ships that is also the thread
 * that ticks and samples input (`KoolThread`), which is what makes the facing readable from an
 * `IntentSource` without a lock.
 */
public class ThirdPersonRig(
    private val resources: RenderResources,
    /** Resolves [target] to an entity. */
    private val netIds: NetIdIndex,
    /** Wall seconds per frame; the easing is a wall-time behaviour, never a tick one. */
    private val frameTime: FrameTime,
    /** The camera this rig moves, and the one a `ModelRenderSystem` draws through. */
    public val camera: ModelCamera = ModelCamera(),
) : RenderSystem {

    /**
     * The entity to follow, or `null` to stay where the camera is. A [NetId], as [CameraRig]'s, because
     * "the local player" outlives a restore and a Fleks `Entity` does not.
     */
    public var target: NetId? = null

    /** Where the mouse's motion comes from: a `KoolPointer`, or [PointerMotion.NONE] for none. */
    public var motion: PointerMotion = PointerMotion.NONE

    /** Which pointer buttons are held, for [turnButton]: a `KoolPointer`, usually the same one as [motion]. */
    public var buttons: PointerState = PointerState.NONE

    /**
     * The pointer button that must be held for the mouse to turn the view, or `null` for the mouse to
     * turn it whenever it moves. Kool numbers the buttons: `0` left, `1` right, `2` middle.
     *
     * `null` suits a captured cursor; a button suits a cursor that is also used to point, and it is how
     * the interface keeps the view still: a press the interface took is never held (`KoolPointer`).
     */
    public var turnButton: Int? = null

    /** Degrees the view turns for each pixel the mouse moves. */
    public var degreesPerPixel: Float = DEFAULT_DEGREES_PER_PIXEL

    /** The direction the camera faces across the ground, in degrees counter-clockwise from +X. */
    public var yawDegrees: Float = DEFAULT_YAW
        set(value) {
            require(value.isFinite()) { "yaw must be a finite number of degrees, was $value" }
            field = wrapDegrees(value)
        }

    /**
     * Degrees the camera looks down at its focus: `0` level with it, larger from higher up. Kept within
     * [minPitchDegrees]..[maxPitchDegrees].
     */
    public var pitchDegrees: Float = DEFAULT_PITCH
        set(value) {
            require(value.isFinite()) { "pitch must be a finite number of degrees, was $value" }
            field = value.coerceIn(minPitchDegrees, maxPitchDegrees)
        }

    /** The lowest [pitchDegrees] goes; negative looks up from below the focus. Above -90. */
    public var minPitchDegrees: Float = DEFAULT_MIN_PITCH
        set(value) {
            require(value > -MAX_PITCH_LIMIT && value < maxPitchDegrees) {
                "the lowest pitch must be above -$MAX_PITCH_LIMIT and below the highest ($maxPitchDegrees), was $value"
            }
            field = value
            pitchDegrees = pitchDegrees
        }

    /**
     * The highest [pitchDegrees] goes. Below 90: straight down, "up" on the screen would be along the
     * line of sight and the view would have no roll to keep.
     */
    public var maxPitchDegrees: Float = DEFAULT_MAX_PITCH
        set(value) {
            require(value < MAX_PITCH_LIMIT && value > minPitchDegrees) {
                "the highest pitch must be below $MAX_PITCH_LIMIT and above the lowest ($minPitchDegrees), was $value"
            }
            field = value
            pitchDegrees = pitchDegrees
        }

    /** World units from the eye to the focus. */
    public var distance: Float = DEFAULT_DISTANCE
        set(value) {
            require(value > 0f && value.isFinite()) { "distance must be a positive number of world units, was $value" }
            field = value
        }

    /** World units above the target's position the camera looks at: its chest rather than its feet. */
    public var focusHeight: Float = DEFAULT_FOCUS_HEIGHT
        set(value) {
            require(value.isFinite()) { "focusHeight must be finite, was $value" }
            field = value
        }

    /** Seconds for the focus to close half the distance to a moved target. `0f` follows exactly. */
    public var followHalfLife: Float = DEFAULT_HALF_LIFE
        set(value) {
            require(value >= 0f && value.isFinite()) { "followHalfLife must be a non-negative number of seconds, was $value" }
            field = value
        }

    /** The ground-plane direction the camera faces: x of a unit vector, `cos(yaw)`. */
    public val forwardX: Float get() = cos(radians(yawDegrees))

    /** The ground-plane direction the camera faces: y of a unit vector, `sin(yaw)`. */
    public val forwardY: Float get() = sin(radians(yawDegrees))

    /** The ground-plane direction to the camera's right, a quarter turn clockwise of forward: x. */
    public val rightX: Float get() = forwardY

    /** The ground-plane direction to the camera's right: y. */
    public val rightY: Float get() = -forwardX

    private var world: World? = null

    // The focus, eased after the target.
    private var focusX = 0f
    private var focusY = 0f
    private var focusZ = 0f

    /** The target the focus was last placed on, so a new one is framed at once rather than eased to. */
    private var framed: NetId? = null

    // Where [targetPosition] put the target this frame.
    private var atX = 0f
    private var atY = 0f
    private var atZ = 0f

    /**
     * Where the target is drawn: `ModelRenderSystem`'s own placer, with no 2D lift, so a target is
     * followed exactly where its model is drawn - interpolated when the world records poses.
     */
    private val placer = ModelPlacer(lift = null)

    override fun onBind(world: World, ctx: GameContext) {
        this.world = world
        placer.bind(world, ctx.clock)
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        // An editor's Scene view is being drawn: the pipeline runs every system again for it, through
        // the editor's own camera. This frame's turn and easing already happened, in the run for the
        // capturable frame; doing them again would ease twice as fast while an editor is open.
        if (resources.viewing.current != null) return
        turn()
        follow(alpha)
        place()
    }

    /** Turns the view by the mouse's motion since last frame, and spends it either way. */
    private fun turn() {
        val button = turnButton
        if (button == null || buttons.isButtonDown(button)) {
            val dx = motion.motionX
            val dy = motion.motionY
            if (dx != 0f || dy != 0f) {
                // Right turns the view right, which is clockwise seen from above: the yaw falls.
                // Down tips the view down: the pitch rises.
                yawDegrees -= dx * degreesPerPixel
                pitchDegrees += dy * degreesPerPixel
            }
        }
        // Spent even when unused, so motion made with the button up does not land when it goes down.
        motion.spendMotion()
    }

    /** Moves the focus towards the target, or onto it when the target is new. */
    private fun follow(alpha: Float) {
        val id = target
        if (id == null || !targetPosition(id, alpha)) {
            if (id == null) framed = null
            return
        }
        val desiredZ = atZ + focusHeight
        if (framed != id) {
            framed = id
            focusX = atX
            focusY = atY
            focusZ = desiredZ
            return
        }
        val t = halfLifeStep(frameTime.frameSeconds, followHalfLife)
        focusX += (atX - focusX) * t
        focusY += (atY - focusY) * t
        focusZ += (desiredZ - focusZ) * t
    }

    /**
     * Writes where the entity [id] names is drawn this frame, at [alpha], into [atX], [atY] and [atZ]:
     * its `Transform3D`, interpolated between the last two ticks as `ModelRenderSystem` draws it.
     *
     * @return false when [id] names no live entity, or one with no `Transform3D`.
     */
    private fun targetPosition(id: NetId, alpha: Float): Boolean {
        val world = world ?: return false
        val entity = netIds.resolveOrNull(id) ?: return false
        if (!placer.place(world, entity, alpha)) return false
        atX = placer.placed.x
        atY = placer.placed.y
        atZ = placer.placed.z
        return true
    }

    /** Puts the eye [distance] behind and above the focus, looking at it. */
    private fun place() {
        val yaw = radians(yawDegrees)
        val pitch = radians(pitchDegrees)
        // The line of sight: across the ground along the yaw, and down by the pitch.
        val level = cos(pitch)
        val lookX = level * cos(yaw)
        val lookY = level * sin(yaw)
        val lookZ = -sin(pitch)
        camera.lookAt(
            focusX - lookX * distance,
            focusY - lookY * distance,
            focusZ - lookZ * distance,
            focusX,
            focusY,
            focusZ,
        )
    }

    override fun toString(): String =
        "ThirdPersonRig(following $target, yaw $yawDegrees, pitch $pitchDegrees, distance $distance)"

    private companion object {
        /** Facing +Y, as `ModelCamera`'s default eye does. */
        const val DEFAULT_YAW: Float = 90f
        const val DEFAULT_PITCH: Float = 20f
        const val DEFAULT_MIN_PITCH: Float = -5f
        const val DEFAULT_MAX_PITCH: Float = 75f
        const val DEFAULT_DISTANCE: Float = 6f
        const val DEFAULT_FOCUS_HEIGHT: Float = 1f
        const val DEFAULT_DEGREES_PER_PIXEL: Float = 0.2f

        /** A tenth of a second, as [CameraRig]'s: attached, but not jittery. */
        const val DEFAULT_HALF_LIFE: Float = 0.1f

        /** Straight up or down: the pitch limits stay strictly inside it. */
        const val MAX_PITCH_LIMIT: Float = 90f

        /** Degrees in the half turn that is [PI] radians. */
        const val HALF_TURN_DEGREES: Double = 180.0

        fun radians(degrees: Float): Float = (degrees * PI / HALF_TURN_DEGREES).toFloat()
    }
}
