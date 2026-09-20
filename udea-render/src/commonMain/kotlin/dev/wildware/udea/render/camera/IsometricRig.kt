package dev.wildware.udea.render.camera

import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelProjection
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * The camera an isometric strategy game is played through (issue #257): tilted above the ground at a
 * fixed angle, **flattened** so that distance shrinks nothing, slid across the ground rather than
 * carried by anybody, and zoomed by how much of the world fits in the picture.
 *
 * [ThirdPersonRig] is its sibling and [CameraRig] its 2D cousin, and all three share the rule that
 * matters most: this **reads the world and writes nothing into it**. It is a [RenderSystem], never a
 * Fleks system, so a dedicated server has no camera at all and a snapshot carries nothing about where
 * anybody was looking. `IsometricRigTest` hashes a world ticked with the rig present and absent and
 * asserts the two agree.
 *
 * ## Why flattened
 *
 * Under a perspective camera a building at the top of the screen is smaller than the same building at
 * the bottom, and its vertical edges lean towards a vanishing point. Neither is wanted here: a player
 * comparing two units across the map is comparing their sizes, and pixel art drawn for one angle has
 * to hold that angle everywhere. So the camera is [ModelProjection.Orthographic], and [viewHeight] -
 * not the distance to the eye - is what a zoom changes.
 *
 * ## Wiring
 *
 * The rig moves a [ModelCamera]; a `ModelRenderSystem` handed the same one draws through it. The rig
 * goes in [dev.wildware.udea.render.RenderPhase.PreRender], so every frame's camera is placed before
 * any model is drawn with it:
 *
 * ```kotlin
 * val camera = ModelCamera()
 * lateinit var rig: IsometricRig
 * registry.register(RenderPhase.PreRender, { r -> IsometricRig(r, registry.frameTime, camera).also { rig = it } })
 * registry.register(RenderPhase.World, { r -> ModelRenderSystem(r, camera, light) })
 * ```
 *
 * ## Driving it
 *
 * [panBy], [zoomBy], [turnLeft] and [turnRight] are what a game calls, from wherever it reads input -
 * edge scroll, a held key, a drag, a wheel. The rig deliberately binds no key and reads no pointer of
 * its own: an isometric game's controls differ too much from one to the next for a default to be
 * right, and every one of them is a call from the game's own input code. Read it where input is
 * sampled, on the render thread, as [ThirdPersonRig]'s facing is read.
 *
 * ## The frame
 *
 * Z is up and the ground is the XY plane, as `Transform3D` has it. [yawDegrees] is the direction the
 * camera faces across the ground, counter-clockwise from +X; [pitchDegrees] is how far it looks down
 * at the ground, and 30 with a yaw of 45 is the classic isometric preset. The camera looks at its
 * **focus**, a point on the ground at [groundZ], from [eyeDistance] away - which under an orthographic
 * projection changes nothing about the size of anything, only what is inside the near and far planes.
 *
 * Everything a game sets is where the camera is **going**; what it draws eases there over
 * [moveHalfLife] wall seconds, sharing [halfLifeStep] with the other two rigs so that the feel does
 * not depend on the frame rate. A turn takes the short way round ([wrapDegrees]). Set the half-life to
 * zero for a camera that arrives at once.
 *
 * ## Threads
 *
 * The render thread, like every [RenderSystem].
 */
public class IsometricRig(
    private val resources: RenderResources,
    /** Wall seconds per frame; the easing is a wall-time behaviour, never a tick one. */
    private val frameTime: FrameTime,
    /** The camera this rig moves, and the one a `ModelRenderSystem` draws through. */
    public val camera: ModelCamera = ModelCamera(),
) : RenderSystem {

    /** The direction the camera faces across the ground, in degrees counter-clockwise from +X. */
    public var yawDegrees: Float = ISO_YAW
        set(value) {
            require(value.isFinite()) { "a yaw is a finite number of degrees, was $value" }
            field = wrapDegrees(value)
        }

    /**
     * Degrees the camera looks down at the ground: 30 is the isometric preset. Strictly between level
     * with the ground, where nothing on it would be visible, and straight down, where "up" on the
     * screen would be along the line of sight and the view would have no roll to keep.
     */
    public var pitchDegrees: Float = ISO_PITCH
        set(value) {
            require(value.isFinite() && value > 0f && value < STRAIGHT_DOWN) {
                "a pitch looks down at the ground from above it: above 0 and below $STRAIGHT_DOWN, was $value"
            }
            field = value
        }

    /**
     * The yaw the quarter turns are measured from, so [snapYaw] puts the view back on one of the four
     * angles this rig was designed around rather than on north. [ISO_YAW] by default, which makes them
     * 45, 135, 225 and 315.
     */
    public var homeYawDegrees: Float = ISO_YAW
        set(value) {
            require(value.isFinite()) { "a home yaw is a finite number of degrees, was $value" }
            field = wrapDegrees(value)
        }

    /** How much of the world the picture is tall, in world units: the zoom. Kept inside its limits. */
    public var viewHeight: Float = DEFAULT_VIEW_HEIGHT
        set(value) {
            require(value > 0f && value.isFinite()) { "a view height is a positive number of world units, was $value" }
            field = value.coerceIn(minViewHeight, maxViewHeight)
        }

    /** The most zoomed in [viewHeight] goes. */
    public var minViewHeight: Float = DEFAULT_MIN_VIEW_HEIGHT
        set(value) {
            require(value > 0f && value < maxViewHeight) {
                "the smallest view height is positive and below the largest ($maxViewHeight), was $value"
            }
            field = value
            viewHeight = viewHeight
        }

    /** The most zoomed out [viewHeight] goes. */
    public var maxViewHeight: Float = DEFAULT_MAX_VIEW_HEIGHT
        set(value) {
            require(value.isFinite() && value > minViewHeight) {
                "the largest view height is above the smallest ($minViewHeight), was $value"
            }
            field = value
            viewHeight = viewHeight
        }

    /** Where on the ground the middle of the picture is: x. Moved by [panBy]. */
    public var focusX: Float = 0f
        set(value) {
            require(value.isFinite()) { "a focus is a finite world position, was $value" }
            field = value
        }

    /** Where on the ground the middle of the picture is: y. */
    public var focusY: Float = 0f
        set(value) {
            require(value.isFinite()) { "a focus is a finite world position, was $value" }
            field = value
        }

    /** How high the ground the camera looks at is, in world units. */
    public var groundZ: Float = 0f
        set(value) {
            require(value.isFinite()) { "a ground height is a finite number of world units, was $value" }
            field = value
        }

    /**
     * World units from the eye to the focus. Under an orthographic projection this changes nothing
     * about how big anything draws - that is [viewHeight]'s job - and it is not a zoom.
     *
     * What it decides is how much of the world is in front of the camera. An orthographic view is a
     * box rather than a cone, so it clips flat at the eye: anything further back along the line of
     * sight than the eye is simply not drawn. The default is therefore well beyond any map's own
     * radius, and a game with a bigger map raises it rather than lowering it.
     */
    public var eyeDistance: Float = DEFAULT_EYE_DISTANCE
        set(value) {
            require(value > 0f && value.isFinite()) { "an eye distance is a positive number of world units, was $value" }
            field = value
        }

    /** Seconds for the picture to close half the distance to where the rig was set. `0f` arrives at once. */
    public var moveHalfLife: Float = DEFAULT_HALF_LIFE
        set(value) {
            require(value >= 0f && value.isFinite()) { "a half-life is a non-negative number of seconds, was $value" }
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

    // Where the picture is, easing after the fields above.
    private var shownYaw = ISO_YAW
    private var shownHeight = DEFAULT_VIEW_HEIGHT
    private var shownX = 0f
    private var shownY = 0f
    private var shownZ = 0f

    /** Whether a frame has been drawn yet: the first one arrives rather than easing in from nowhere. */
    private var placed = false

    /**
     * Slides the view across the ground, in world units, along the directions the picture faces:
     * [right] across it and [forward] into it. A game's edge scroll and arrow keys call this.
     *
     * The directions are [yawDegrees]', the angle the rig is turning **to**, rather than the
     * part-turned angle a frame in the middle of a quarter turn is showing. A pan held down through a
     * turn therefore runs straight, instead of curving round with the picture.
     */
    public fun panBy(right: Float, forward: Float) {
        require(right.isFinite() && forward.isFinite()) { "a pan is a finite distance, was ($right, $forward)" }
        focusX += right * rightX + forward * forwardX
        focusY += right * rightY + forward * forwardY
    }

    /**
     * Multiplies how much of the world fits in the picture: [factor] below 1 zooms in, above 1 zooms
     * out. Stops at [minViewHeight] and [maxViewHeight]. A wheel notch calls this.
     */
    public fun zoomBy(factor: Float) {
        require(factor > 0f && factor.isFinite()) { "a zoom factor is a positive number, was $factor" }
        viewHeight = (viewHeight * factor).coerceIn(minViewHeight, maxViewHeight)
    }

    /** Turns the view a quarter turn anticlockwise, seen from above. */
    public fun turnLeft() {
        yawDegrees += QUARTER_TURN
    }

    /** Turns the view a quarter turn clockwise. */
    public fun turnRight() {
        yawDegrees -= QUARTER_TURN
    }

    /**
     * Puts a freely turned view back on the nearest quarter turn from [homeYawDegrees]: what a game
     * calls when a turn drag is released, if it wants the four fixed angles an isometric game usually
     * has rather than any angle at all.
     */
    public fun snapYaw() {
        val fromHome = wrapDegrees(yawDegrees - homeYawDegrees)
        yawDegrees = homeYawDegrees + round(fromHome / QUARTER_TURN) * QUARTER_TURN
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        // An editor's Scene view is being drawn: the pipeline runs every system again for it, through
        // the editor's own camera. This frame's easing already happened, in the run for the capturable
        // frame; doing it again would ease twice as fast while an editor is open.
        if (resources.viewing.current != null) return
        ease()
        place()
    }

    /** Moves the picture towards where the rig was set, or onto it on the first frame. */
    private fun ease() {
        if (!placed) {
            placed = true
            shownYaw = yawDegrees
            shownHeight = viewHeight
            shownX = focusX
            shownY = focusY
            shownZ = groundZ
            return
        }
        val t = halfLifeStep(frameTime.frameSeconds, moveHalfLife)
        // The short way round: 170 to -170 is twenty degrees, not three hundred and forty.
        shownYaw = wrapDegrees(shownYaw + wrapDegrees(yawDegrees - shownYaw) * t)
        shownHeight += (viewHeight - shownHeight) * t
        shownX += (focusX - shownX) * t
        shownY += (focusY - shownY) * t
        shownZ += (groundZ - shownZ) * t
    }

    /** Writes the eased view onto the camera: back along the yaw, up by the pitch, flattened. */
    private fun place() {
        val yaw = radians(shownYaw)
        val pitch = radians(pitchDegrees)
        val level = cos(pitch) * eyeDistance
        camera.lookAt(
            shownX - level * cos(yaw),
            shownY - level * sin(yaw),
            shownZ + sin(pitch) * eyeDistance,
            shownX,
            shownY,
            shownZ,
        )
        camera.projection = ModelProjection.Orthographic
        camera.viewHeight = shownHeight
        // An orthographic view clips flat at the eye, so the near plane sits just in front of it and
        // the far one well beyond the focus: the whole map has to lie between the two.
        camera.near = NEAR_MARGIN
        camera.far = eyeDistance * FAR_REACH
    }

    override fun toString(): String =
        "IsometricRig(over ($focusX, $focusY), yaw $yawDegrees, pitch $pitchDegrees, $viewHeight units tall)"

    private companion object {

        /** The isometric preset's yaw: looking across the ground diagonally, so a grid reads as diamonds. */
        const val ISO_YAW: Float = 45f

        /** The isometric preset's pitch: 30 degrees above the ground. */
        const val ISO_PITCH: Float = 30f

        const val QUARTER_TURN: Float = 90f

        /** Straight down: the pitch stays strictly inside it. */
        const val STRAIGHT_DOWN: Float = 90f

        /** Twenty world units tall, as a `ModelCamera` starts: a few buildings across. */
        const val DEFAULT_VIEW_HEIGHT: Float = 20f
        const val DEFAULT_MIN_VIEW_HEIGHT: Float = 4f
        const val DEFAULT_MAX_VIEW_HEIGHT: Float = 120f

        /**
         * Far enough back that a map a couple of hundred units across lies entirely in front of the
         * eye, which is what an orthographic view needs; it costs nothing, because the distance is
         * not a zoom.
         */
        const val DEFAULT_EYE_DISTANCE: Float = 200f

        /** A twelfth of a second: the camera keeps up with the hand without snapping. */
        const val DEFAULT_HALF_LIFE: Float = 0.08f

        /** Just in front of the eye, in world units: an orthographic view has nothing behind it. */
        const val NEAR_MARGIN: Float = 0.1f

        /** Multiples of [eyeDistance] the far plane sits at, so the ground beyond the focus is drawn. */
        const val FAR_REACH: Float = 2f
    }
}
