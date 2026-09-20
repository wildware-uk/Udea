package dev.wildware.udea.render.pick

import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelProjection
import dev.wildware.udea.render.support.CameraProjections
import dev.wildware.udea.render.view.ViewPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which world point is under a pixel, through a [ModelCamera] (issue #262).
 *
 * ## What this suite is actually measuring, and against what
 *
 * [CameraPick] un-projects **analytically**: it rebuilds the camera's basis from its eye, its target
 * and its lens, rather than inverting the two matrices [ModelCamera] publishes. That is faster and
 * allocation-free, and it is also a second implementation of the same geometry - which is exactly the
 * kind of thing that agrees with itself and with nothing else. So nothing here asserts against numbers
 * this file worked out by hand:
 *
 * - every assertion goes through [CameraProjections], which multiplies by
 *   [ModelCamera.writeViewMatrix] and [ModelCamera.writeProjectionMatrix] and by nothing else;
 * - and those two matrices are held to the pixels a real Kool context draws by `GlIsoCameraTest`,
 *   which predicts a shape's centroid from them and reads it back out of a capture.
 *
 * So a defect in the un-projection shows up here as a round trip that does not come back, and a
 * defect in the matrices shows up under GL. Neither can hide behind the other.
 *
 * ## The acceptance criterion, stated as a number
 *
 * Issue #262 asks for the ground point under the cursor "within one render pixel, at 3 zoom levels".
 * [`the ground point under a pixel comes back to that pixel, at three zoom levels`] is that sentence:
 * it un-projects a grid of pixels onto z = 0 and projects each result back, at view heights of 8, 20
 * and 60 world units, and reports the worst error in pixels. The tolerance is [ONE_PIXEL].
 *
 * [`a camera read at the wrong zoom misses by the zoom error`] is its control: it un-projects through
 * a camera whose view height is 10% wrong and shows the round trip moving by about 10% of the pixel's
 * distance from the middle of the picture - hundreds of pixels at the edge. Without it, "within one
 * pixel" would be a tolerance nobody has ever seen breached.
 */
class CameraPickTest {

    // --- the criterion --------------------------------------------------------------------

    @Test
    fun `the ground point under a pixel comes back to that pixel, at three zoom levels`() {
        for (zoom in ZOOMS) {
            val camera = isoCamera(zoom)
            val pick = CameraPick(camera)
            pick.fit(WIDTH, HEIGHT)
            var worst = 0f
            var worstAt = ""
            for (pixel in gridPixels()) {
                val hit = WorldPoint()
                assertTrue(
                    pick.groundUnder(pixel.x, pixel.y, GROUND_Z, hit),
                    "no ground under pixel (${pixel.x}, ${pixel.y}) at a view height of $zoom",
                )
                val error = pixelError(camera, hit, pixel)
                if (error > worst) {
                    worst = error
                    worstAt = "(${pixel.x}, ${pixel.y}) -> (${hit.x}, ${hit.y})"
                }
            }
            println("CameraPickTest: view height $zoom: worst round trip $worst px at $worstAt")
            assertTrue(
                worst < ONE_PIXEL,
                "at a view height of $zoom world units the ground point under a pixel came back " +
                    "$worst pixels away, past the one-pixel criterion; worst at $worstAt",
            )
        }
    }

    @Test
    fun `a camera read at the wrong zoom misses by the zoom error`() {
        // The control for the criterion above. A view height 10% larger covers 10% more world per
        // pixel, so a pixel `d` pixels from the middle un-projects to a point that draws back
        // `0.1 * d` pixels further out. Anything else means the measurement has no scale.
        val drawnThrough = isoCamera(ZOOMS[1])
        val readAs = isoCamera(ZOOMS[1] * ZOOM_ERROR)
        val pick = CameraPick(readAs)
        pick.fit(WIDTH, HEIGHT)

        for (pixel in gridPixels()) {
            val hit = WorldPoint()
            assertTrue(pick.groundUnder(pixel.x, pixel.y, GROUND_Z, hit), "no ground under $pixel")
            val moved = pixelError(drawnThrough, hit, pixel)
            val fromMiddle = hypot(pixel.x - WIDTH / 2f, pixel.y - HEIGHT / 2f)
            val predicted = (ZOOM_ERROR - 1f) * fromMiddle
            println(
                "CameraPickTest: control: pixel (${pixel.x}, ${pixel.y}) is $fromMiddle from the " +
                    "middle, moved $moved px, predicted $predicted px",
            )
            assertTrue(
                abs(moved - predicted) < ONE_PIXEL,
                "a 10% zoom error moved the pixel $moved px when the arithmetic of the error says " +
                    "$predicted px: the round trip is not measuring the zoom",
            )
        }
    }

    @Test
    fun `the middle pixel is the point the camera looks at`() {
        val camera = isoCamera(ZOOMS[1])
        val pick = CameraPick(camera)
        pick.fit(WIDTH, HEIGHT)

        val hit = WorldPoint()
        assertTrue(pick.groundUnder(WIDTH / 2f, HEIGHT / 2f, GROUND_Z, hit), "no ground in the middle")

        assertEquals(FOCUS_X, hit.x, ONE_HUNDREDTH_OF_A_UNIT, "the middle of the picture is not over the focus")
        assertEquals(FOCUS_Y, hit.y, ONE_HUNDREDTH_OF_A_UNIT, "the middle of the picture is not over the focus")
        assertEquals(GROUND_Z, hit.z, ONE_HUNDREDTH_OF_A_UNIT, "the hit is not on the ground plane")
    }

    // --- the perspective lens, which picking must also serve --------------------------------

    @Test
    fun `the ground point under a pixel comes back to that pixel under a perspective lens`() {
        val camera = isoCamera(ZOOMS[1]).apply {
            projection = ModelProjection.Perspective
            fovYDegrees = 50f
            // Near enough for the cone to matter: at 200 units away a 50-degree cone is a map.
            lookAt(FOCUS_X + 0f, FOCUS_Y - 12f, 9f, FOCUS_X, FOCUS_Y, GROUND_Z)
        }
        val pick = CameraPick(camera)
        pick.fit(WIDTH, HEIGHT)

        var worst = 0f
        for (pixel in gridPixels()) {
            val hit = WorldPoint()
            assertTrue(pick.groundUnder(pixel.x, pixel.y, GROUND_Z, hit), "no ground under $pixel")
            worst = maxOf(worst, pixelError(camera, hit, pixel))
        }
        println("CameraPickTest: perspective: worst round trip $worst px")
        assertTrue(worst < ONE_PIXEL, "a perspective un-projection came back $worst pixels away")
    }

    @Test
    fun `a pixel whose ray never reaches the ground has no ground under it`() {
        // Tilted up above the horizon: the ray leaves the ground behind it and climbs away.
        val camera = ModelCamera().apply {
            projection = ModelProjection.Perspective
            fovYDegrees = 60f
            lookAt(0f, -10f, 2f, 0f, 0f, 20f)
        }
        val pick = CameraPick(camera)
        pick.fit(WIDTH, HEIGHT)

        assertFalse(
            pick.groundUnder(WIDTH / 2f, HEIGHT.toFloat(), GROUND_Z, WorldPoint()),
            "a camera looking above the horizon found ground at the top of the picture",
        )
    }

    // --- projecting forwards, which is what an entity pick measures against ------------------

    @Test
    fun `a world point projects to the pixel the camera's own matrices put it at`() {
        val camera = isoCamera(ZOOMS[0])
        val pick = CameraPick(camera)
        pick.fit(WIDTH, HEIGHT)

        for (point in SCATTERED_POINTS) {
            val out = ViewPoint()
            assertTrue(pick.project(point.x, point.y, point.z, out), "$point projected nowhere")
            val expected = CameraProjections.pixelOf(camera, point.x, point.y, point.z, WIDTH, HEIGHT)
            assertEquals(expected.x, out.x, ONE_PIXEL, "$point is at the wrong x")
            // The matrices are read y-down as an image is; a view pixel is y-up from the bottom.
            assertEquals(HEIGHT - expected.y, out.y, ONE_PIXEL, "$point is at the wrong y")
        }
    }

    @Test
    fun `a point behind the eye projects nowhere`() {
        val camera = ModelCamera().apply {
            projection = ModelProjection.Perspective
            lookAt(0f, -10f, 0f, 0f, 0f, 0f)
        }
        val pick = CameraPick(camera)
        pick.fit(WIDTH, HEIGHT)

        assertFalse(pick.project(0f, -20f, 0f, ViewPoint()), "a point behind the eye was given a pixel")
    }

    @Test
    fun `depth grows with distance along the line of sight`() {
        val camera = ModelCamera().apply { lookAt(0f, -10f, 0f, 0f, 0f, 0f) }
        val pick = CameraPick(camera)
        pick.fit(WIDTH, HEIGHT)

        assertEquals(10f, pick.depthOf(0f, 0f, 0f), ONE_HUNDREDTH_OF_A_UNIT, "the focus is its own distance away")
        assertEquals(15f, pick.depthOf(0f, 5f, 0f), ONE_HUNDREDTH_OF_A_UNIT, "a point beyond the focus is further")
        assertTrue(pick.depthOf(0f, -20f, 0f) < 0f, "a point behind the eye has a negative depth")
    }

    @Test
    fun `picking refuses a picture with no pixels in it`() {
        val pick = CameraPick(isoCamera(ZOOMS[1]))

        val failure = runCatching { pick.groundUnder(0f, 0f, GROUND_Z, WorldPoint()) }.exceptionOrNull()

        assertTrue(
            failure is IllegalStateException,
            "picking before the picture has a size answered with $failure rather than saying so",
        )
    }

    // --- helpers ----------------------------------------------------------------------------

    /** An isometric camera over [FOCUS_X], [FOCUS_Y]: pitch 30, yaw 45, flattened, [height] tall. */
    private fun isoCamera(height: Float): ModelCamera {
        val yaw = (ISO_YAW * PI / HALF_TURN).toFloat()
        val pitch = (ISO_PITCH * PI / HALF_TURN).toFloat()
        val level = cos(pitch) * EYE_DISTANCE
        return ModelCamera().apply {
            projection = ModelProjection.Orthographic
            viewHeight = height
            near = 0.1f
            far = EYE_DISTANCE * 2f
            lookAt(
                FOCUS_X - level * cos(yaw),
                FOCUS_Y - level * sin(yaw),
                GROUND_Z + sin(pitch) * EYE_DISTANCE,
                FOCUS_X,
                FOCUS_Y,
                GROUND_Z,
            )
        }
    }

    /** How far, in pixels, [hit] draws from [pixel] through [camera]. */
    private fun pixelError(camera: ModelCamera, hit: WorldPoint, pixel: ViewPoint): Float {
        val drawn = CameraProjections.pixelOf(camera, hit.x, hit.y, hit.z, WIDTH, HEIGHT)
        return hypot(drawn.x - pixel.x, (HEIGHT - drawn.y) - pixel.y)
    }

    /** Pixels spread over the whole picture, corners included: a middle-only test proves nothing. */
    private fun gridPixels(): List<ViewPoint> {
        val points = ArrayList<ViewPoint>()
        for (column in 0..GRID) {
            for (row in 0..GRID) {
                points += ViewPoint(WIDTH.toFloat() * column / GRID, HEIGHT.toFloat() * row / GRID)
            }
        }
        return points
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720

        /** The criterion, in render pixels. */
        const val ONE_PIXEL = 1f

        const val ONE_HUNDREDTH_OF_A_UNIT = 0.01f

        /** Three zoom levels, as the issue asks: close in, the default, and well out. */
        val ZOOMS = floatArrayOf(8f, 20f, 60f)

        /** The control's deliberate error: 10% more world in the picture than the camera drew. */
        const val ZOOM_ERROR = 1.1f

        /** Off the origin, so a bug that drops the focus shows up rather than cancelling. */
        const val FOCUS_X = 17.5f
        const val FOCUS_Y = -9.25f
        const val GROUND_Z = 0f

        const val ISO_YAW = 45.0
        const val ISO_PITCH = 30.0
        const val HALF_TURN = 180.0
        const val EYE_DISTANCE = 200f

        /** Pixels per side of the sampled grid. 8 gives 81 pixels, corners and edges included. */
        const val GRID = 8

        val SCATTERED_POINTS = listOf(
            WorldPoint().apply { set(FOCUS_X, FOCUS_Y, 0f) },
            WorldPoint().apply { set(FOCUS_X + 3f, FOCUS_Y - 2f, 1.5f) },
            WorldPoint().apply { set(FOCUS_X - 2.5f, FOCUS_Y + 4f, 0f) },
            WorldPoint().apply { set(FOCUS_X + 1f, FOCUS_Y + 1f, 3f) },
        )
    }
}
