package dev.wildware.udea.render.camera

import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.input.PointerPosition
import dev.wildware.udea.render.input.PointerState
import dev.wildware.udea.render.input.PointerWheel
import kotlin.math.abs

/**
 * The two ways a strategy game's camera is moved with the mouse, wired to an [IsometricRig]
 * (issue #262): **push the cursor against the edge of the screen** and **drag with the middle
 * button**. The wheel zooms.
 *
 * ## Why this is not on the rig
 *
 * [IsometricRig] says, at length, that it binds no key and reads no pointer: an isometric game's
 * controls differ too much from one to the next for a default to be right, and every one of them is
 * a call the game makes. That is still true, and this does not change it - it is one such caller,
 * shipped because edge scroll and a middle-drag are the two controls *every* game of the kind has,
 * and because writing them out correctly involves the frame clock, the screen's edges and a
 * conversion from pixels to world units that is easy to get subtly wrong.
 *
 * A game that wants something else does not use this and calls [IsometricRig.panBy] itself. Nothing
 * here is reached unless it is registered.
 *
 * ## It is a camera, and a camera is not an order
 *
 * Nothing here reaches an `Intent`. Panning the view is not something the simulation is told about,
 * any more than turning your head is: two players watching the same replay may look at different
 * corners of the map and see the same match. That is why this is a [RenderSystem] and why
 * `WorldPointer` - which *does* feed the tick - is a different class.
 *
 * ## Wall seconds, deliberately
 *
 * An edge scroll moves the view at so many world units **per second**, taken from [FrameTime], not
 * per tick and not per frame. Per frame would scroll twice as fast on a 120Hz monitor; per tick
 * would make the camera stutter whenever a frame covered two ticks. Seconds exist in `udea-render`
 * for exactly this.
 *
 * ## Threads
 *
 * The render thread, like every [RenderSystem].
 */
public class IsoPanControl(
    /** Tells this system when it is being drawn for an editor's Scene view rather than for the game. */
    private val resources: RenderResources,
    /** Wall seconds per frame: an edge scroll is a speed, not a step. */
    private val frameTime: FrameTime,
    /** The camera this moves. */
    public val rig: IsometricRig,
    /** Where the cursor is. [PointerPosition.NONE] disables the edge scroll and the drag. */
    private val position: PointerPosition,
    /** Which buttons are down: what a middle-drag is made of. */
    private val buttons: PointerState = PointerState.NONE,
    /** The wheel. [PointerWheel.NONE] disables the zoom. */
    private val wheel: PointerWheel = PointerWheel.NONE,
) : RenderSystem {

    /**
     * How close to an edge, in pixels, the cursor starts pushing the view. Zero turns the edge
     * scroll off.
     */
    public var edgeMargin: Float = DEFAULT_EDGE_MARGIN
        set(value) {
            require(value >= 0f && value.isFinite()) { "an edge margin is a non-negative number of pixels, was $value" }
            field = value
        }

    /**
     * World units a second the view slides when the cursor is hard against an edge, at the default
     * zoom.
     *
     * It is scaled by how zoomed out the view is, so that a scroll crosses the *picture* in about
     * the same time however much of the map is in it - which is what a player means by "the camera
     * speed", and is not what a fixed number of world units a second does.
     */
    public var edgeSpeed: Float = DEFAULT_EDGE_SPEED
        set(value) {
            require(value >= 0f && value.isFinite()) { "an edge speed is a non-negative number of units a second, was $value" }
            field = value
        }

    /** The button a drag-pan is held with: the middle one by default, as `PointerState` numbers them. */
    public var panButton: Int = MIDDLE_BUTTON
        set(value) {
            require(value >= 0) { "a pointer button is a non-negative index, was $value" }
            field = value
        }

    /**
     * What one notch **away from the player** multiplies the view height by. Below 1 means pushing
     * the wheel away zooms in, which is the default and what most strategy games do; above 1 is the
     * other way round, for a game or a player who wants it.
     */
    public var zoomPerNotch: Float = DEFAULT_ZOOM_PER_NOTCH
        set(value) {
            require(value > 0f && value.isFinite()) { "a zoom factor is a positive number, was $value" }
            field = value
        }

    /** Whether a drag-pan is in progress: the button is down and the cursor has been read since. */
    public var isDragging: Boolean = false
        private set

    private var lastX = 0f
    private var lastY = 0f

    override fun render(target: OffscreenTarget, alpha: Float) {
        // The editor's Scene view is drawn by running every system again; this frame's camera has
        // already been moved. `IsometricRig` skips its easing here for the same reason.
        if (resources.viewing.current != null) return
        step(frameTime.frameSeconds, target.width, target.height)
    }

    /**
     * One frame of panning and zooming, over [seconds] of wall time and a [width] x [height]
     * picture.
     *
     * Public so the whole control can be driven, and tested, with no window and no device: hand it
     * a [PointerPosition] that says where the cursor is and call this.
     */
    public fun step(seconds: Float, width: Int, height: Int) {
        require(seconds >= 0f && seconds.isFinite()) { "a frame is a non-negative number of seconds, was $seconds" }
        require(width > 0 && height > 0) { "a picture has a positive size, was ${width}x$height" }
        zoom()
        if (!position.isPointerOver) {
            isDragging = false
            return
        }
        val x = position.pointerX
        val y = position.pointerY
        if (drag(x, y, height)) return
        edgeScroll(x, y, width, height, seconds)
    }

    /** Spends the wheel. Runs whether or not the cursor is over the window: a notch is a notch. */
    private fun zoom() {
        val notches = wheel.scrollY
        wheel.spendScroll()
        if (notches == 0f) return
        // A notch is a *multiplier*, so two notches zoom by the square rather than by twice as much.
        // Compounding is what makes the wheel feel the same at every zoom level; adding does not.
        var factor = 1f
        var left = abs(notches)
        val step = if (notches > 0f) zoomPerNotch else 1f / zoomPerNotch
        while (left >= 1f) {
            factor *= step
            left -= 1f
        }
        // A part-notch - a trackpad - scales towards the whole one rather than being dropped.
        if (left > 0f) factor *= 1f + (step - 1f) * left
        rig.zoomBy(factor)
    }

    /**
     * The middle-drag: the ground stays under the cursor.
     *
     * The pixels the cursor moved are turned into world units through the view's own height, so a
     * drag across half the picture moves the view across half of what the picture holds - at every
     * zoom level, which is the property that makes a drag feel like grabbing the map.
     *
     * @return true when a drag is in progress, so the edge scroll stays out of the way of it.
     */
    private fun drag(x: Float, y: Float, height: Int): Boolean {
        if (!buttons.isButtonDown(panButton)) {
            isDragging = false
            return false
        }
        if (!isDragging) {
            isDragging = true
            lastX = x
            lastY = y
            return true
        }
        val unitsPerPixel = rig.viewHeight / height
        // Window pixels run down from the top and the view runs up it, so a cursor moved down drags
        // the ground down, which is the view moving *forward*. Both signs are inverted because the
        // ground follows the hand rather than the camera following it.
        rig.panBy(-(x - lastX) * unitsPerPixel, (y - lastY) * unitsPerPixel)
        lastX = x
        lastY = y
        return true
    }

    /** The edge scroll: how far into the margin the cursor is decides how fast, up to [edgeSpeed]. */
    private fun edgeScroll(x: Float, y: Float, width: Int, height: Int, seconds: Float) {
        if (edgeMargin <= 0f || edgeSpeed <= 0f || seconds <= 0f) return
        val right = push(x, width.toFloat())
        // Window pixels run down; forward is up the picture, so the sign is turned round here.
        val forward = -push(y, height.toFloat())
        if (right == 0f && forward == 0f) return
        // Scaled by the zoom, so a scroll crosses the picture in the same time however far out it is.
        val speed = edgeSpeed * seconds * rig.viewHeight / ZOOM_REFERENCE
        rig.panBy(right * speed, forward * speed)
    }

    /**
     * How hard the cursor at [at] pushes against the two ends of a [span]-pixel edge: `-1` hard
     * against the low end, `+1` hard against the high end, and `0` anywhere in the middle.
     *
     * A ramp rather than a step, so easing the cursor into the corner of the screen creeps rather
     * than jumping to full speed, and a cursor one pixel past the margin is not a jolt.
     */
    private fun push(at: Float, span: Float): Float {
        if (at < edgeMargin) return -((edgeMargin - at).coerceAtMost(edgeMargin) / edgeMargin)
        val fromEnd = span - at
        if (fromEnd < edgeMargin) return (edgeMargin - fromEnd).coerceAtMost(edgeMargin) / edgeMargin
        return 0f
    }

    override fun toString(): String =
        "IsoPanControl($rig, edge ${edgeMargin}px${if (isDragging) ", dragging" else ""})"

    private companion object {

        /** Kool's middle button, which `PointerState` documents as index 2. */
        const val MIDDLE_BUTTON = 2

        /** Pixels: wide enough to hit with a flick of the wrist, narrow enough not to trigger by accident. */
        const val DEFAULT_EDGE_MARGIN = 24f

        /** World units a second at [ZOOM_REFERENCE], which crosses the default view in about a second and a half. */
        const val DEFAULT_EDGE_SPEED = 14f

        /** The view height [DEFAULT_EDGE_SPEED] is quoted at: `IsometricRig`'s own default. */
        const val ZOOM_REFERENCE = 20f

        /** A notch away shows about a sixth less world; a notch back shows about a fifth more. */
        const val DEFAULT_ZOOM_PER_NOTCH = 1f / 1.2f
    }
}
