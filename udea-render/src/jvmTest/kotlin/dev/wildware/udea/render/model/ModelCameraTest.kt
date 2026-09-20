package dev.wildware.udea.render.model

import dev.wildware.udea.render.support.CameraProjections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a [ModelCamera] flattens the world with (issue #257): the projection it chooses, and the two
 * matrices it writes out as plain floats for a game to un-project through.
 *
 * Everything here is arithmetic, so it needs no render context. That the matrices agree with the
 * picture a real Kool context draws through the same camera is `GlIsoCameraTest`'s: it predicts a
 * pixel from these numbers and reads that pixel back out of a capture.
 *
 * The property that separates the two projections is one sentence, and every test below is a way of
 * asking it: **under perspective a thing's size on screen depends on how far away it is, and under
 * orthographic it does not.**
 */
class ModelCameraTest {

    // --- the view matrix ------------------------------------------------------------------

    @Test
    fun `the view matrix puts the eye at the origin`() {
        val camera = ModelCamera().apply { lookAt(3f, -7f, 4f, 0f, 0f, 0f) }

        val eye = CameraProjections.times(camera.writeViewMatrix(FloatArray(16)), 3f, -7f, 4f)

        assertNear(0f, eye.x, "the eye is not at the origin of eye space")
        assertNear(0f, eye.y, "the eye is not at the origin of eye space")
        assertNear(0f, eye.z, "the eye is not at the origin of eye space")
    }

    @Test
    fun `the view matrix puts what the camera looks at straight ahead, down -Z`() {
        val camera = ModelCamera().apply { lookAt(3f, -7f, 4f, 1f, 2f, 0.5f) }

        val target = CameraProjections.times(camera.writeViewMatrix(FloatArray(16)), 1f, 2f, 0.5f)

        assertNear(0f, target.x, "what the camera looks at is off to one side")
        assertNear(0f, target.y, "what the camera looks at is above or below the middle")
        assertTrue(target.z < 0f, "what the camera looks at is behind it: z is ${target.z}")
        assertNear(distance(3f, -7f, 4f, 1f, 2f, 0.5f), -target.z, "the target is not its own distance away")
    }

    @Test
    fun `the view matrix keeps Z up on the screen`() {
        // Z is up in this world (Transform3D), so a point straight above what the camera looks at
        // must land above the middle of the picture and nowhere to either side.
        val camera = ModelCamera().apply { lookAt(0f, -10f, 6f, 0f, 0f, 0f) }

        val above = CameraProjections.times(camera.writeViewMatrix(FloatArray(16)), 0f, 0f, 2f)

        assertNear(0f, above.x, "a point straight up leaned to one side")
        assertTrue(above.y > 0f, "a point straight up did not land above the middle: y is ${above.y}")
    }

    // --- perspective, which is what a camera does today -----------------------------------

    @Test
    fun `a camera is perspective until it is told otherwise`() {
        assertEquals(ModelProjection.Perspective, ModelCamera().projection)
    }

    @Test
    fun `under perspective a thing at twice the distance draws half the size`() {
        val camera = ModelCamera().apply { lookAt(0f, -10f, 0f, 0f, 0f, 0f) }

        // The same one-unit rise, ten units away and twenty units away.
        val near = CameraProjections.clipOf(camera, 0f, 0f, 1f, ASPECT).ndcY
        val far = CameraProjections.clipOf(camera, 0f, 10f, 1f, ASPECT).ndcY

        assertTrue(near > 0f && far > 0f, "both points are above the middle: near $near, far $far")
        assertNear(2f, near / far, "twice as far away must be half the size: $near against $far")
    }

    @Test
    fun `under perspective the top of the picture is half the field of view up`() {
        val camera = ModelCamera().apply {
            lookAt(0f, -10f, 0f, 0f, 0f, 0f)
            projection = ModelProjection.Perspective
            fovYDegrees = 45f
        }

        // tan(22.5 degrees) * 10 units away is exactly the top edge of the picture.
        val top = 10f * tan(radians(22.5f))

        assertNear(1f, CameraProjections.clipOf(camera, 0f, 0f, top, ASPECT).ndcY, "the field of view does not reach the top edge")
    }

    // --- orthographic, which is what an isometric game wants -------------------------------

    @Test
    fun `under orthographic a thing is the same size however far away it is`() {
        val camera = ModelCamera().apply {
            lookAt(0f, -10f, 0f, 0f, 0f, 0f)
            projection = ModelProjection.Orthographic
            viewHeight = 8f
        }

        // Ten units away and fifty: both well inside the camera's far plane, so the only thing that
        // differs between them is the depth.
        val near = CameraProjections.clipOf(camera, 0f, 0f, 1f, ASPECT).ndcY
        val far = CameraProjections.clipOf(camera, 0f, 40f, 1f, ASPECT).ndcY

        assertNear(near, far, "an orthographic camera drew the same rise at two depths at two sizes")
    }

    @Test
    fun `under orthographic the picture is exactly its view height tall`() {
        val camera = ModelCamera().apply {
            lookAt(0f, -10f, 0f, 0f, 0f, 0f)
            projection = ModelProjection.Orthographic
            viewHeight = 8f
        }

        assertNear(1f, CameraProjections.clipOf(camera, 0f, 0f, 4f, ASPECT).ndcY, "half the view height is not the top edge")
        assertNear(-1f, CameraProjections.clipOf(camera, 0f, 0f, -4f, ASPECT).ndcY, "half the view height is not the bottom edge")
    }

    @Test
    fun `under orthographic the picture is its view height times the aspect wide`() {
        val camera = ModelCamera().apply {
            lookAt(0f, -10f, 0f, 0f, 0f, 0f)
            projection = ModelProjection.Orthographic
            viewHeight = 8f
        }

        assertNear(1f, CameraProjections.clipOf(camera, 4f * ASPECT, 0f, 0f, ASPECT).ndcX, "the view is not the aspect ratio wide")
    }

    @Test
    fun `under orthographic parallel world lines stay parallel on the picture`() {
        // Two pillars standing on the ground, one on each side of what the camera looks at, seen
        // from a tilted camera. Each is a line along +Z; under a projection with no vanishing point
        // the two must come out the same width apart at the top as at the bottom, and neither may
        // lean. Under perspective they converge, which the test below shows.
        val camera = isoCamera(ModelProjection.Orthographic)

        val leanLeft = lean(camera, x = -PILLAR_OFFSET)
        val leanRight = lean(camera, x = PILLAR_OFFSET)

        assertNear(0f, leanLeft, "the left pillar leans: its top is $leanLeft from its foot across the picture")
        assertNear(0f, leanRight, "the right pillar leans: its top is $leanRight from its foot across the picture")
    }

    @Test
    fun `the control - under perspective those same lines do converge`() {
        // The test above asserts a difference is zero. A measurement that is zero under both
        // projections would say nothing about either, so this is the same measurement where it
        // must not be zero.
        val camera = isoCamera(ModelProjection.Perspective)

        val leanLeft = lean(camera, x = -PILLAR_OFFSET)
        val leanRight = lean(camera, x = PILLAR_OFFSET)

        assertTrue(abs(leanLeft) > MIN_CONVERGENCE, "a perspective camera drew the left pillar with no lean at all: $leanLeft")
        assertTrue(abs(leanRight) > MIN_CONVERGENCE, "a perspective camera drew the right pillar with no lean at all: $leanRight")
        assertTrue(leanLeft * leanRight < 0f, "the two pillars must lean towards each other: $leanLeft and $leanRight")
    }

    @Test
    fun `under orthographic the same shape lands wherever the thing stands`() {
        val camera = isoCamera(ModelProjection.Orthographic)

        val here = rise(camera, x = 0f, y = 0f)
        val overThere = rise(camera, x = 9f, y = -7f)

        assertNear(here, overThere, "an orthographic camera drew the same pillar two sizes")
    }

    // --- what the numbers must be --------------------------------------------------------

    @Test
    fun `a view height must be a positive number of world units`() {
        assertFailsWith<IllegalArgumentException> { ModelCamera().viewHeight = 0f }
        assertFailsWith<IllegalArgumentException> { ModelCamera().viewHeight = -3f }
        assertFailsWith<IllegalArgumentException> { ModelCamera().viewHeight = Float.NaN }
    }

    @Test
    fun `writing a matrix hands back the array it was given, and fills every cell`() {
        val camera = ModelCamera()
        val out = FloatArray(16) { Float.NaN }

        assertTrue(out === camera.writeViewMatrix(out), "writeViewMatrix did not hand back its own array")
        assertTrue(out.none { it.isNaN() }, "writeViewMatrix left a cell unwritten: ${out.toList()}")

        val projection = FloatArray(16) { Float.NaN }
        assertTrue(projection === camera.writeProjectionMatrix(projection, ASPECT), "writeProjectionMatrix did not hand back its own array")
        assertTrue(projection.none { it.isNaN() }, "writeProjectionMatrix left a cell unwritten: ${projection.toList()}")
    }

    @Test
    fun `a matrix must be written into room for sixteen floats`() {
        assertFailsWith<IllegalArgumentException> { ModelCamera().writeViewMatrix(FloatArray(15)) }
        assertFailsWith<IllegalArgumentException> { ModelCamera().writeProjectionMatrix(FloatArray(9), ASPECT) }
    }

    @Test
    fun `toString says which projection the camera is`() {
        assertTrue("Perspective" in ModelCamera().toString(), "a perspective camera does not say so: ${ModelCamera()}")
        val ortho = ModelCamera().apply { projection = ModelProjection.Orthographic; viewHeight = 12f }
        assertTrue("Orthographic" in ortho.toString() && "12" in ortho.toString(), "an orthographic camera does not say so: $ortho")
    }

    // --- fixture ---------------------------------------------------------------------------

    /**
     * A camera at the isometric preset's angles, looking at the origin from [DISTANCE] away.
     *
     * The distance is short on purpose. A perspective camera's convergence falls away with distance -
     * that is what makes a long lens look flat - so a camera far enough out would lean the pillars by
     * less than this file's tolerance and the control below would fail for being a weak scene rather
     * than for the arithmetic being wrong. At [DISTANCE] the lean is about 0.1 in normalised units,
     * five times [MIN_CONVERGENCE].
     */
    private fun isoCamera(projection: ModelProjection): ModelCamera {
        // Yaw 45 degrees, pitch 30 degrees above the ground, 40 units out: the preset of issue #257.
        val yaw = radians(45f)
        val pitch = radians(30f)
        val level = cos(pitch) * DISTANCE
        return ModelCamera().apply {
            lookAt(
                -level * cos(yaw),
                -level * sin(yaw),
                sin(pitch) * DISTANCE,
                0f,
                0f,
                0f,
            )
            near = 0.1f
            far = 200f
            this.projection = projection
            viewHeight = 24f
            fovYDegrees = 35f
        }
    }

    /** How far across the picture a pillar's top sits from its foot, in normalised units. */
    private fun lean(camera: ModelCamera, x: Float): Float {
        val foot = CameraProjections.clipOf(camera, x, 0f, 0f, ASPECT).ndcX
        val top = CameraProjections.clipOf(camera, x, 0f, PILLAR_HEIGHT, ASPECT).ndcX
        return top - foot
    }

    /** How tall a pillar standing at [x], [y] draws, in normalised units. */
    private fun rise(camera: ModelCamera, x: Float, y: Float): Float {
        val foot = CameraProjections.clipOf(camera, x, y, 0f, ASPECT).ndcY
        val top = CameraProjections.clipOf(camera, x, y, PILLAR_HEIGHT, ASPECT).ndcY
        return top - foot
    }

    private fun distance(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun assertNear(expected: Float, actual: Float, what: String) {
        assertTrue(abs(actual - expected) < TOLERANCE, "$what: expected $expected, was $actual")
    }

    private fun radians(degrees: Float): Float = (degrees * PI / HALF_TURN).toFloat()

    private companion object {
        const val HALF_TURN = 180.0
        const val ASPECT = 16f / 9f
        const val TOLERANCE = 1e-3f
        const val PILLAR_HEIGHT = 3f
        const val PILLAR_OFFSET = 4f
        const val DISTANCE = 12f

        /** How far a perspective camera must lean a pillar, in normalised units, to count as leaning. */
        const val MIN_CONVERGENCE = 0.02f
    }
}
