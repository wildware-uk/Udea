package dev.wildware.udea.render.pick

import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelProjection
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.ViewRay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Which world point is drawn at a pixel, and which pixel a world point is drawn at, through a
 * [ModelCamera] (issue #262). **The half of picking that has nothing to do with entities.**
 *
 * A game's pointer is a pixel and its orders are world points, and this is the only place the two
 * meet. A move order is [groundUnder]; an entity pick is [project] over the boxes the render systems
 * report (see [EntityPicker]); a building's footprint preview is both.
 *
 * ## Presentation only, and structurally so
 *
 * This reads a camera, and a camera is presentation. Nothing here writes into the world, and nothing
 * in the simulation may hold one of these: a screen coordinate must not survive into
 * `Simulation.step()`, which is why `WorldPointer` turns a pixel into a world point on the render
 * thread and the `Intent` carries only the world point. That is the rule issue #262 was written
 * around - "the sim never reads the camera" - and it is kept by where this class is rather than by
 * anybody remembering it.
 *
 * ## Why the basis and not the inverse matrix
 *
 * [ModelCamera.writeViewMatrix] and [ModelCamera.writeProjectionMatrix] hand out the two matrices
 * (issue #257), and un-projecting by inverting their product would be sixteen floats of work per
 * pixel plus an inverse that is numerically delicate near the far plane. Instead this rebuilds the
 * camera's own basis - forward, right, up - the same way the view matrix does, and runs the
 * projection's arithmetic backwards. It allocates nothing and it needs no matrix.
 *
 * The cost of that choice is a second implementation of the same geometry, which is exactly the kind
 * of thing that agrees with itself and with nothing else. So `CameraPickTest` asserts every result
 * against [dev.wildware.udea.render.model.ModelCamera]'s **published matrices** rather than against
 * numbers worked out by hand, and `GlIsoCameraTest` holds those matrices to the pixels a real Kool
 * context draws. A defect in here is a round trip that does not come back; a defect in the matrices
 * is a GL test that moves.
 *
 * ## The pixels
 *
 * View pixels, `x` right from the left edge and `y` **up** from the bottom - the convention
 * `EditorCamera.projectOrbit` and `WorldViewport.toView` already use, so there is one of them in this
 * module rather than two. A backend reports a pointer with `y` down from the top, and the one place
 * that is turned round is where the pointer enters: `WorldPointer.aim`.
 *
 * ## Threads
 *
 * The render thread, like everything else that reads a camera. It holds no state but the picture's
 * size: the camera is read afresh on every call, so a camera moved between two calls is seen moved.
 */
public class CameraPick(
    /** The camera being seen through. Read on every call, never copied. */
    public val camera: ModelCamera,
) {

    /** The picture's width in pixels, from [fit]. */
    public var width: Int = 0
        private set

    /** The picture's height in pixels, from [fit]. */
    public var height: Int = 0
        private set

    // The camera's basis, rebuilt by `read()` at the top of every public call. Fields rather than
    // locals so nothing allocates: `read` cannot return six floats without a tuple.
    private var forwardX = 0f
    private var forwardY = 1f
    private var forwardZ = 0f
    private var rightX = 1f
    private var rightY = 0f
    private var upX = 0f
    private var upY = 0f
    private var upZ = 1f

    /** Makes this pick through a picture [width] x [height] pixels. Called when the frame resizes. */
    public fun fit(width: Int, height: Int) {
        require(width > 0 && height > 0) { "a picture has a positive size, was ${width}x$height" }
        this.width = width
        this.height = height
    }

    /**
     * Writes into [out] the world point of the plane `z = `[groundZ] drawn at view pixel ([viewX],
     * [viewY]) - **the point under the cursor**, which is what a move order is made of.
     *
     * @return false when the line drawn at that pixel never reaches that plane: a camera looking
     *   above the horizon, or one looking along the plane rather than at it. [out] is untouched then,
     *   because a caller that ignores the answer should not be handed a plausible wrong point.
     */
    public fun groundUnder(viewX: Float, viewY: Float, groundZ: Float, out: WorldPoint): Boolean {
        ray(viewX, viewY, scratch)
        val slope = scratch.directionZ
        // Parallel to the plane: the line either lies in it or never meets it, and neither is a hit.
        if (abs(slope) < FLAT) return false
        val along = (groundZ - scratch.originZ) / slope
        // Behind the eye. Under a perspective lens that is the sky above the horizon; under an
        // orthographic one it is the half of the world the flat near plane cuts away.
        if (along < 0f) return false
        out.set(
            scratch.originX + scratch.directionX * along,
            scratch.originY + scratch.directionY * along,
            groundZ,
        )
        return true
    }

    /**
     * Writes into [out] the line of world points drawn at view pixel ([viewX], [viewY]): where it
     * starts and the unit direction it runs in, away from the eye.
     *
     * Under [ModelProjection.Perspective] every line starts at the eye and they fan out. Under
     * [ModelProjection.Orthographic] they are all parallel and each starts at its own point on the
     * flat near face, which is why a picture with no vanishing point can still be picked in.
     */
    public fun ray(viewX: Float, viewY: Float, out: ViewRay) {
        read()
        val across = viewX / width * 2f - 1f
        val up = viewY / height * 2f - 1f
        val aspect = width.toFloat() / height
        when (camera.projection) {
            ModelProjection.Perspective -> {
                val half = tan(radians(camera.fovYDegrees) / 2f)
                val sideways = across * half * aspect
                val upward = up * half
                val dx = forwardX + rightX * sideways + upX * upward
                val dy = forwardY + rightY * sideways + upY * upward
                val dz = forwardZ + upZ * upward
                val length = sqrt(dx * dx + dy * dy + dz * dz)
                out.originX = camera.eyeX
                out.originY = camera.eyeY
                out.originZ = camera.eyeZ
                out.directionX = dx / length
                out.directionY = dy / length
                out.directionZ = dz / length
            }
            ModelProjection.Orthographic -> {
                val halfHeight = camera.viewHeight / 2f
                val sideways = across * halfHeight * aspect
                val upward = up * halfHeight
                out.originX = camera.eyeX + rightX * sideways + upX * upward
                out.originY = camera.eyeY + rightY * sideways + upY * upward
                out.originZ = camera.eyeZ + upZ * upward
                out.directionX = forwardX
                out.directionY = forwardY
                out.directionZ = forwardZ
            }
        }
    }

    /**
     * Writes into [out] the view pixel world point ([x], [y], [z]) is drawn at: [groundUnder] run
     * forwards, and what an entity's box is measured with.
     *
     * @return false when the point is nearer than [ModelCamera.near], and so drawn nowhere. The far
     *   plane is deliberately not tested: a box that reaches past it is still drawn, clipped, and a
     *   pick that refused it would refuse the thing the player can see.
     */
    public fun project(x: Float, y: Float, z: Float, out: ViewPoint): Boolean {
        read()
        val rx = x - camera.eyeX
        val ry = y - camera.eyeY
        val rz = z - camera.eyeZ
        val depth = rx * forwardX + ry * forwardY + rz * forwardZ
        if (depth <= camera.near) return false
        val sideways = rx * rightX + ry * rightY
        val upward = rx * upX + ry * upY + rz * upZ
        val aspect = width.toFloat() / height
        val ndcX: Float
        val ndcY: Float
        when (camera.projection) {
            ModelProjection.Perspective -> {
                val half = tan(radians(camera.fovYDegrees) / 2f)
                ndcX = sideways / depth / (half * aspect)
                ndcY = upward / depth / half
            }
            ModelProjection.Orthographic -> {
                val halfHeight = camera.viewHeight / 2f
                ndcX = sideways / (halfHeight * aspect)
                ndcY = upward / halfHeight
            }
        }
        out.x = (ndcX + 1f) / 2f * width
        out.y = (ndcY + 1f) / 2f * height
        return true
    }

    /**
     * How far in front of the eye world point ([x], [y], [z]) is, along the line of sight, in world
     * units. Negative behind it.
     *
     * What decides which of two entities under the pointer is in front. It is the *depth*, not the
     * distance: under an orthographic lens two boxes side by side are the same depth apart from the
     * eye however far across the picture they are, which is the ordering a flattened picture draws.
     */
    public fun depthOf(x: Float, y: Float, z: Float): Float {
        read()
        return (x - camera.eyeX) * forwardX + (y - camera.eyeY) * forwardY + (z - camera.eyeZ) * forwardZ
    }

    override fun toString(): String = "CameraPick($camera, ${width}x$height)"

    /** One line of world points, reused by [groundUnder] so a pick per frame allocates nothing. */
    private val scratch = ViewRay()

    /**
     * Rebuilds the camera's basis: forward, right across the picture, and up it.
     *
     * The same arithmetic [ModelCamera.writeViewMatrix] writes into its rows, and deliberately so -
     * a camera looking straight down has no roll to take from world +Z and falls back to +Y up the
     * picture there as it does here, so a pick agrees with the picture in the one case where "up"
     * is a choice rather than a consequence.
     */
    private fun read() {
        check(width > 0 && height > 0) { "$this has no picture to pick in: call fit(width, height) first" }
        var fx = camera.targetX - camera.eyeX
        var fy = camera.targetY - camera.eyeY
        var fz = camera.targetZ - camera.eyeZ
        val length = sqrt(fx * fx + fy * fy + fz * fz)
        if (length > 0f) {
            fx /= length
            fy /= length
            fz /= length
        } else {
            fx = 0f
            fy = 1f
            fz = 0f
        }
        var sx = fy
        var sy = -fx
        val across = sqrt(sx * sx + sy * sy)
        if (across > 0f) {
            sx /= across
            sy /= across
        } else {
            sx = 1f
            sy = 0f
        }
        forwardX = fx
        forwardY = fy
        forwardZ = fz
        rightX = sx
        rightY = sy
        // Up is right x forward, and right has no z, which is what shortens this. There is no
        // `upZ` term for `right` for the same reason: the picture's right always lies on the ground.
        upX = sy * fz
        upY = -sx * fz
        upZ = sx * fy - sy * fx
    }

    private companion object {

        /**
         * How near to parallel a line may run to the ground before it counts as never meeting it.
         *
         * Not zero: at 1e-7 of slope the hit is a hundred million units away, which is a number that
         * poisons everything downstream rather than a point anybody clicked on.
         */
        const val FLAT = 1e-6f

        const val HALF_TURN_DEGREES = 180.0

        fun radians(degrees: Float): Float = (degrees * PI / HALF_TURN_DEGREES).toFloat()
    }
}
