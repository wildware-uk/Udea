package dev.wildware.udea.render.view

import dev.wildware.udea.render.camera.Camera2D
import dev.wildware.udea.render.camera.ExtendViewport
import dev.wildware.udea.render.draw.Projection2D
import dev.wildware.udea.render.model.ModelCamera
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Which of the editor camera's two cameras a person is moving. */
public enum class ViewDimension {

    /** Pan and zoom over the ground plane, as a 2D game is seen. */
    TwoD,

    /** Orbit, pan and zoom round a point, Z up, as a 3D world is seen. */
    ThreeD,
}

/** A point in a view, in pixels from its bottom-left corner. Mutable and reused, like `Pose`. */
public class ViewPoint(public var x: Float = 0f, public var y: Float = 0f) {
    override fun toString(): String = "ViewPoint($x, $y)"
}

/**
 * The line of world points drawn at one view pixel (issue #237): where it starts, and the unit
 * direction it runs in, away from the eye. What a 3D gizmo drag holds to a handle's line or plane.
 * Mutable and reused, like [ViewPoint].
 */
public class ViewRay {
    public var originX: Float = 0f
    public var originY: Float = 0f
    public var originZ: Float = 0f
    public var directionX: Float = 0f
    public var directionY: Float = 0f
    public var directionZ: Float = -1f

    override fun toString(): String = "ViewRay(($originX, $originY, $originZ) along ($directionX, $directionY, $directionZ))"
}

/**
 * The Scene tab's camera (issue #234): the editor's own look at the world, apart from the game's.
 *
 * It is **presentation state**. Nothing in it is a `Tick`, nothing in it is read by the simulation,
 * and nothing about it reaches a snapshot or a capture: a person moving it changes only what the
 * Scene tab shows. It holds two cameras, because a world can be drawn both ways at once - a 2D game's
 * sprites through [camera2D], a 3D world's models through the orbit - and [dimension] says which one
 * a drag moves.
 *
 * ## 2D: pan and zoom
 *
 * [camera2D] and an [ExtendViewport], the same two things a game's `CameraRig` projects with, so a
 * Scene view at the game's position and zoom frames exactly what the Game view frames. [adopt] copies
 * them from the game's rig on the first frame a Scene view is drawn.
 *
 * ## 3D: orbit round a centre, Z up
 *
 * The eye sits [distance] from ([targetX], [targetY], [targetZ]), turned [yawDegrees] about Z from
 * +X and raised [pitchDegrees] above the ground plane. Pitch stops short of straight up and down,
 * where "up" would be along the line of sight and the view would have no roll to keep.
 *
 * ## Why the projection is written out here
 *
 * [project] is the arithmetic a person's pointer is hit-tested with, and Kool's camera is what draws.
 * Both are the standard look-at and perspective of OpenGL, and `GlViewportOrbitTest` holds them to
 * agreeing: a model drawn at a world point shows up at the pixel [project] names for it.
 *
 * Render thread only, like the view it belongs to: the pointer handlers that move it and the frame
 * that draws through it both run there.
 */
public class EditorCamera(
    worldWidth: Float = DEFAULT_WORLD_WIDTH,
    worldHeight: Float = DEFAULT_WORLD_HEIGHT,
) {

    /** Which camera a drag moves. The Scene tab's toggle writes it. */
    public var dimension: ViewDimension = ViewDimension.TwoD

    /** Where the 2D camera looks and how far out it is zoomed. */
    public val camera2D: Camera2D = Camera2D()

    private var viewport: ExtendViewport = ExtendViewport(worldWidth, worldHeight)

    /** World units to view pixels in 2D, as of the last [fit]. */
    public val projection: Projection2D = Projection2D()

    /** The orbit's centre, in world units. */
    public var targetX: Float = 0f

    /** @see targetX */
    public var targetY: Float = 0f

    /** @see targetX */
    public var targetZ: Float = 0f

    /** Degrees about Z from +X to the eye, seen from above. */
    public var yawDegrees: Float = DEFAULT_YAW

    /** Degrees above the ground plane the eye sits. Kept inside ±[MAX_PITCH]. */
    public var pitchDegrees: Float = DEFAULT_PITCH
        set(value) {
            field = value.coerceIn(-MAX_PITCH, MAX_PITCH)
        }

    /** World units from the eye to the centre. */
    public var distance: Float = DEFAULT_DISTANCE
        set(value) {
            require(value > 0f && value.isFinite()) { "an orbit distance is a positive number of world units, was $value" }
            field = value
        }

    /** Vertical field of view in degrees. */
    public var fovYDegrees: Float = DEFAULT_FOV

    /** Nearest distance drawn, in world units. */
    public var near: Float = DEFAULT_NEAR

    /** Farthest distance drawn, in world units. */
    public var far: Float = DEFAULT_FAR

    private var width: Int = 0
    private var height: Int = 0

    /** Whether [adopt] has placed the 2D camera, which it does only once. */
    internal var placed2D: Boolean = false
        private set

    /** Whether [adopt] has placed the orbit, which it does only once. */
    internal var placed3D: Boolean = false
        private set

    // The orbit's frame, recomputed by `frame()` before each projection. Fields rather than a
    // returned object: a pointer drag projects every move.
    private var eyeX = 0f
    private var eyeY = 0f
    private var eyeZ = 0f
    private var forwardX = 0f
    private var forwardY = 0f
    private var forwardZ = 0f
    private var sideX = 0f
    private var sideY = 0f
    private var sideZ = 0f
    private var upX = 0f
    private var upY = 0f
    private var upZ = 0f

    /** Fits both cameras to a view of [width] x [height] pixels, and republishes [projection]. */
    public fun fit(width: Int, height: Int) {
        require(width > 0 && height > 0) { "a view cannot be ${width}x$height" }
        if (width != this.width || height != this.height) viewport.update(width, height)
        this.width = width
        this.height = height
        viewport.project(camera2D, projection)
    }

    /**
     * Moves the 2D camera so the world follows a pointer dragged by ([dxPixels], [dyPixels]) view
     * pixels, y up. In 3D, moves the orbit's centre across the view the same way.
     */
    public fun pan(dxPixels: Float, dyPixels: Float) {
        checkFitted()
        when (dimension) {
            ViewDimension.TwoD -> {
                camera2D.position.x -= dxPixels / projection.scaleX
                camera2D.position.y -= dyPixels / projection.scaleY
                viewport.project(camera2D, projection)
            }
            ViewDimension.ThreeD -> {
                frame()
                // The world units one pixel covers at the centre's depth.
                val unitsPerPixel = 2f * distance * tan(radians(fovYDegrees) / 2f) / height
                val dx = -dxPixels * unitsPerPixel
                val dy = -dyPixels * unitsPerPixel
                targetX += sideX * dx + upX * dy
                targetY += sideY * dx + upY * dy
                targetZ += sideZ * dx + upZ * dy
            }
        }
    }

    /**
     * Zooms by [factor] - above 1 shows more world - keeping the world point under the pointer at
     * ([viewX], [viewY]) where it is. In 3D it moves the eye towards or away from the centre.
     */
    public fun zoomAt(factor: Float, viewX: Float, viewY: Float) {
        require(factor > 0f && factor.isFinite()) { "a zoom factor is a positive number, was $factor" }
        checkFitted()
        when (dimension) {
            ViewDimension.TwoD -> {
                val beforeX = (viewX - projection.offsetX) / projection.scaleX
                val beforeY = (viewY - projection.offsetY) / projection.scaleY
                camera2D.zoom *= factor
                viewport.project(camera2D, projection)
                camera2D.position.x += beforeX - (viewX - projection.offsetX) / projection.scaleX
                camera2D.position.y += beforeY - (viewY - projection.offsetY) / projection.scaleY
                viewport.project(camera2D, projection)
            }
            ViewDimension.ThreeD -> dolly(factor)
        }
    }

    /** Turns the orbit by [yawDelta] degrees about Z and [pitchDelta] degrees up. */
    public fun orbit(yawDelta: Float, pitchDelta: Float) {
        yawDegrees = wrap(yawDegrees + yawDelta)
        pitchDegrees += pitchDelta
    }

    /** Multiplies the orbit's distance by [factor]: below 1 moves in. */
    public fun dolly(factor: Float) {
        require(factor > 0f && factor.isFinite()) { "a dolly factor is a positive number, was $factor" }
        distance *= factor
    }

    /**
     * Writes into [out] the view pixel world point ([x], [y], [z]) is drawn at, through the camera
     * [dimension] names; `z` is ignored in 2D, where the world is the ground plane.
     *
     * @return false when the point is behind the 3D eye, or nearer than [near], and so drawn nowhere.
     */
    public fun project(x: Float, y: Float, z: Float, out: ViewPoint): Boolean {
        checkFitted()
        if (dimension == ViewDimension.TwoD) {
            out.x = projection.pixelX(x)
            out.y = projection.pixelY(y)
            return true
        }
        return projectOrbit(x, y, z, out)
    }

    /**
     * Writes into [out] the view pixel world point ([x], [y], [z]) is drawn at through the 3D orbit,
     * whichever camera [dimension] names: a Scene view draws its models through the orbit in 2D as
     * well as in 3D, so picking a model (issue #235) projects its box through here.
     *
     * @return false when the point is behind the eye, or nearer than [near], and so drawn nowhere.
     */
    public fun projectOrbit(x: Float, y: Float, z: Float, out: ViewPoint): Boolean {
        checkFitted()
        frame()
        val rx = x - eyeX
        val ry = y - eyeY
        val rz = z - eyeZ
        val depth = rx * forwardX + ry * forwardY + rz * forwardZ
        if (depth <= near) return false
        val t = tan(radians(fovYDegrees) / 2f)
        val aspect = width.toFloat() / height
        val ndcX = (rx * sideX + ry * sideY + rz * sideZ) / depth / (t * aspect)
        val ndcY = (rx * upX + ry * upY + rz * upZ) / depth / t
        out.x = (ndcX + 1f) / 2f * width
        out.y = (ndcY + 1f) / 2f * height
        return true
    }

    /**
     * How far in front of the 3D eye world point ([x], [y], [z]) is, along the line of sight: the
     * depth that decides which of two models under the pointer is in front (issue #235). Negative
     * behind the eye.
     */
    public fun depthOf(x: Float, y: Float, z: Float): Float {
        frame()
        return (x - eyeX) * forwardX + (y - eyeY) * forwardY + (z - eyeZ) * forwardZ
    }

    /** Writes into [out] the ground-plane world point under view pixel ([viewX], [viewY]), in 2D. */
    public fun unproject(viewX: Float, viewY: Float, out: ViewPoint) {
        checkFitted()
        out.x = (viewX - projection.offsetX) / projection.scaleX
        out.y = (viewY - projection.offsetY) / projection.scaleY
    }

    /**
     * Writes into [out] the line of world points drawn at view pixel ([viewX], [viewY]), through the
     * camera [dimension] names (issue #237): in 3D from the eye out through the pixel; in 2D straight
     * down onto the ground-plane point [unproject] names, since the 2D camera looks down Z.
     */
    public fun ray(viewX: Float, viewY: Float, out: ViewRay) {
        checkFitted()
        if (dimension == ViewDimension.TwoD) {
            out.originX = (viewX - projection.offsetX) / projection.scaleX
            out.originY = (viewY - projection.offsetY) / projection.scaleY
            out.originZ = 0f
            out.directionX = 0f
            out.directionY = 0f
            out.directionZ = -1f
            return
        }
        frame()
        // [projectOrbit] backwards: a pixel's normalised position, spread by the field of view.
        val t = tan(radians(fovYDegrees) / 2f)
        val across = (viewX / width * 2f - 1f) * t * (width.toFloat() / height)
        val up = (viewY / height * 2f - 1f) * t
        val dx = forwardX + sideX * across + upX * up
        val dy = forwardY + sideY * across + upY * up
        val dz = forwardZ + sideZ * across + upZ * up
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        out.originX = eyeX
        out.originY = eyeY
        out.originZ = eyeZ
        out.directionX = dx / length
        out.directionY = dy / length
        out.directionZ = dz / length
    }

    /**
     * How many world units one view pixel covers at world point ([x], [y], [z]), through the camera
     * [dimension] names (issue #237): the zoom in 2D; in 3D, what a pixel spans at the point's depth.
     */
    public fun unitsPerPixelAt(x: Float, y: Float, z: Float): Float {
        checkFitted()
        if (dimension == ViewDimension.TwoD) return 1f / projection.scaleX
        val depth = maxOf(depthOf(x, y, z), near)
        return 2f * depth * tan(radians(fovYDegrees) / 2f) / height
    }

    /**
     * Starts the 2D camera where [camera] is, framed by a viewport of [viewport]'s minimum world size:
     * the Scene view opens on what the Game view shows. Once; later calls change nothing, so a person
     * who has moved the Scene view is not put back.
     */
    internal fun adopt(camera: Camera2D, viewport: ExtendViewport) {
        if (placed2D) return
        placed2D = true
        this.viewport = ExtendViewport(viewport.minWorldWidth, viewport.minWorldHeight)
        if (width > 0) this.viewport.update(width, height)
        camera2D.position.x = camera.position.x
        camera2D.position.y = camera.position.y
        camera2D.zoom = camera.zoom
        if (width > 0) this.viewport.project(camera2D, projection)
    }

    /** Starts the orbit on [camera]'s eye, round what it looks at. Once, like the 2D [adopt]. */
    internal fun adopt(camera: ModelCamera) {
        if (placed3D) return
        placed3D = true
        targetX = camera.targetX
        targetY = camera.targetY
        targetZ = camera.targetZ
        val dx = camera.eyeX - camera.targetX
        val dy = camera.eyeY - camera.targetY
        val dz = camera.eyeZ - camera.targetZ
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length > 0f) {
            distance = length
            yawDegrees = degrees(atan2(dy, dx))
            pitchDegrees = degrees(asin(dz / length))
        }
        fovYDegrees = camera.fovYDegrees
        near = camera.near
        far = camera.far
    }

    /** Writes the orbit into [into]: the eye, the centre, the field of view and the clip range. */
    internal fun writeOrbit(into: ModelCamera) {
        frame()
        into.lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ)
        into.fovYDegrees = fovYDegrees
        into.near = near
        into.far = far
    }

    /** Recomputes the eye and its three axes from the orbit. */
    private fun frame() {
        val yaw = radians(yawDegrees)
        val pitch = radians(pitchDegrees)
        eyeX = targetX + distance * cos(pitch) * cos(yaw)
        eyeY = targetY + distance * cos(pitch) * sin(yaw)
        eyeZ = targetZ + distance * sin(pitch)
        forwardX = (targetX - eyeX) / distance
        forwardY = (targetY - eyeY) / distance
        forwardZ = (targetZ - eyeZ) / distance
        // side = forward x Z, normalised; never degenerate while pitch stays inside ±MAX_PITCH.
        val sx = forwardY
        val sy = -forwardX
        val sideLength = sqrt(sx * sx + sy * sy)
        sideX = sx / sideLength
        sideY = sy / sideLength
        sideZ = 0f
        // up = side x forward.
        upX = sideY * forwardZ - sideZ * forwardY
        upY = sideZ * forwardX - sideX * forwardZ
        upZ = sideX * forwardY - sideY * forwardX
    }

    private fun checkFitted() {
        check(width > 0) { "$this has not been fitted to a view yet" }
    }

    override fun toString(): String = "EditorCamera($dimension, 2D at ${camera2D.position} zoom ${camera2D.zoom}, " +
        "orbit ($targetX, $targetY, $targetZ) yaw $yawDegrees pitch $pitchDegrees distance $distance)"

    private companion object {
        /** `CameraRig`'s own default framing, for a view with no game camera to adopt. */
        const val DEFAULT_WORLD_WIDTH: Float = 32f
        const val DEFAULT_WORLD_HEIGHT: Float = 18f

        /** From -Y, looking along +Y, a little above the ground: `ModelCamera`'s default eye. */
        const val DEFAULT_YAW: Float = -90f
        const val DEFAULT_PITCH: Float = 30f
        const val DEFAULT_DISTANCE: Float = 10f
        const val DEFAULT_FOV: Float = 45f
        const val DEFAULT_NEAR: Float = 0.1f
        const val DEFAULT_FAR: Float = 1000f

        /** Degrees short of vertical the pitch stops at. */
        const val MAX_PITCH: Float = 89f

        const val HALF_TURN: Float = 180f
        const val FULL_TURN: Float = 360f

        fun radians(degrees: Float): Float = (degrees * PI / HALF_TURN).toFloat()

        fun degrees(radians: Float): Float = (radians * HALF_TURN / PI).toFloat()

        /** [degrees] folded into (-180, 180]. */
        fun wrap(degrees: Float): Float {
            var folded = degrees % FULL_TURN
            if (folded <= -HALF_TURN) folded += FULL_TURN
            if (folded > HALF_TURN) folded -= FULL_TURN
            return folded
        }
    }
}
