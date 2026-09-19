package dev.wildware.udea.render.view

import dev.wildware.udea.render.camera.Camera2D
import dev.wildware.udea.render.camera.ExtendViewport
import dev.wildware.udea.render.model.ModelCamera
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Scene tab's camera as arithmetic (issue #234): what it does to a world point when a person
 * drags, scrolls or orbits. The GL half - that Kool draws the world where this arithmetic says it
 * is - is `GlWorldViewportTest` and `GlViewportOrbitTest`.
 */
class EditorCameraTest {

    @Test
    fun `in 2D the camera centre is the middle of the view, and zoom 2 shows twice the world`() {
        val camera = EditorCamera(worldWidth = 32f, worldHeight = 18f)
        camera.camera2D.position.x = 10f
        camera.camera2D.position.y = -4f
        camera.fit(WIDTH, HEIGHT)
        val at = ViewPoint()

        camera.project(10f, -4f, 0f, at)
        assertNear(WIDTH / 2f, at.x, "the camera's centre is not the middle of the view")
        assertNear(HEIGHT / 2f, at.y, "the camera's centre is not the middle of the view")
        val oneUnitAt1 = pixelsPerUnit(camera)

        camera.camera2D.zoom = 2f
        camera.fit(WIDTH, HEIGHT)
        assertNear(oneUnitAt1 / 2f, pixelsPerUnit(camera), "zoom 2 does not show twice the world")
    }

    @Test
    fun `a 2D pan drags the world with the pointer`() {
        val camera = EditorCamera(worldWidth = 32f, worldHeight = 18f)
        camera.fit(WIDTH, HEIGHT)
        val before = ViewPoint()
        camera.project(3f, 2f, 0f, before)

        camera.pan(100f, -40f)

        val after = ViewPoint()
        camera.project(3f, 2f, 0f, after)
        assertNear(before.x + 100f, after.x, "the world did not move right with the pointer")
        assertNear(before.y - 40f, after.y, "the world did not move down with the pointer")
    }

    @Test
    fun `a 2D zoom keeps the world point under the pointer where it is`() {
        val camera = EditorCamera(worldWidth = 32f, worldHeight = 18f)
        camera.fit(WIDTH, HEIGHT)
        val under = ViewPoint()
        camera.unproject(500f, 300f, under)
        val (worldX, worldY) = under.x to under.y

        camera.zoomAt(2f, 500f, 300f)

        assertNear(2f, camera.camera2D.zoom, "the zoom was not applied")
        val after = ViewPoint()
        camera.project(worldX, worldY, 0f, after)
        assertNear(500f, after.x, "the point under the pointer slid sideways")
        assertNear(300f, after.y, "the point under the pointer slid vertically")
    }

    @Test
    fun `in 3D the orbit centre is the middle of the view from every angle`() {
        val camera = orbitCamera()
        val at = ViewPoint()
        for (yaw in listOf(-90f, 0f, 45f, 170f)) {
            for (pitch in listOf(-30f, 10f, 60f)) {
                camera.yawDegrees = yaw
                camera.pitchDegrees = pitch
                assertTrue(camera.project(1f, 2f, 0f, at), "the orbit centre is behind the camera at $yaw/$pitch")
                assertNear(WIDTH / 2f, at.x, "yaw $yaw pitch $pitch")
                assertNear(HEIGHT / 2f, at.y, "yaw $yaw pitch $pitch")
            }
        }
    }

    @Test
    fun `orbiting half a turn puts a point on the other side of the view, and Z stays up`() {
        val camera = orbitCamera()
        // The eye is on -Y looking along +Y: +X is on the right, +Z is up.
        camera.yawDegrees = -90f
        camera.pitchDegrees = 20f
        val right = ViewPoint()
        val up = ViewPoint()
        camera.project(2f, 2f, 0f, right)
        camera.project(1f, 2f, 1f, up)
        assertTrue(right.x > WIDTH / 2f + 20f, "+X is not on the right from -Y: ${right.x}")
        assertTrue(up.y > HEIGHT / 2f + 20f, "+Z is not up: ${up.y}")

        camera.orbit(180f, 0f)

        val after = ViewPoint()
        camera.project(2f, 2f, 0f, after)
        assertNear(90f, camera.yawDegrees, "the orbit did not turn half a turn")
        assertNear(WIDTH - right.x, after.x, "half a turn did not mirror the point across the middle")
        camera.project(1f, 2f, 1f, up)
        assertTrue(up.y > HEIGHT / 2f + 20f, "+Z is not up after the orbit: ${up.y}")
    }

    @Test
    fun `the orbit pitch stops short of straight up and straight down`() {
        val camera = orbitCamera()
        camera.orbit(0f, 500f)
        assertTrue(camera.pitchDegrees < 90f, "pitched past straight down: ${camera.pitchDegrees}")
        val at = ViewPoint()
        assertTrue(camera.project(1f, 2f, 0f, at), "looking straight down lost the centre")
        assertNear(WIDTH / 2f, at.x, "looking straight down lost the centre")

        camera.orbit(0f, -1000f)
        assertTrue(camera.pitchDegrees > -90f, "pitched past straight up: ${camera.pitchDegrees}")
    }

    @Test
    fun `dollying in moves a point off centre further from the middle, and a point behind is refused`() {
        val camera = orbitCamera()
        camera.yawDegrees = -90f
        camera.pitchDegrees = 0f
        val far = ViewPoint()
        camera.project(2f, 2f, 0f, far)

        camera.dolly(0.5f)

        val near = ViewPoint()
        camera.project(2f, 2f, 0f, near)
        assertNear(5f, camera.distance, "the dolly did not halve the distance")
        assertTrue(near.x - WIDTH / 2f > (far.x - WIDTH / 2f) * 1.5f, "closer did not look bigger: ${far.x} -> ${near.x}")
        assertFalse(camera.project(1f, -10f, 0f, near), "a point behind the eye was projected")
    }

    @Test
    fun `adopting the game's cameras opens the Scene view on the Game view's framing`() {
        val camera = EditorCamera()
        val game = Camera2D().apply {
            position.x = 40f
            position.y = 7f
            zoom = 1.5f
        }
        camera.adopt(game, ExtendViewport(320f, 180f))
        camera.fit(WIDTH, HEIGHT)
        val gameViewport = ExtendViewport(320f, 180f).apply { update(WIDTH, HEIGHT) }
        val expected = dev.wildware.udea.render.draw.Projection2D()
        gameViewport.project(game, expected)
        assertNear(expected.scaleX, camera.projection.scaleX, "the Scene view does not start at the game's zoom")
        assertNear(expected.offsetX, camera.projection.offsetX, "the Scene view does not start where the game looks")
        assertNear(expected.offsetY, camera.projection.offsetY, "the Scene view does not start where the game looks")

        camera.adopt(ModelCamera(eyeX = 0f, eyeY = -10f, eyeZ = 10f, targetX = 0f, targetY = 0f, targetZ = 0f))
        assertNear(-90f, camera.yawDegrees, "the orbit does not start on the game's eye")
        assertNear(45f, camera.pitchDegrees, "the orbit does not start on the game's eye")
        assertNear(14.142136f, camera.distance, "the orbit does not start at the game's distance")
    }

    @Test
    fun `the 3D ray through a pixel passes through every world point drawn at that pixel`() {
        val camera = orbitCamera().apply {
            yawDegrees = -60f
            pitchDegrees = 25f
        }
        val ray = ViewRay()
        for ((x, y, z) in listOf(Triple(1f, 2f, 0f), Triple(3f, -1f, 2f), Triple(-2f, 4f, -1f))) {
            val at = ViewPoint()
            assertTrue(camera.project(x, y, z, at))
            camera.ray(at.x, at.y, ray)
            // The point's distance from the line the ray runs along.
            val t = (x - ray.originX) * ray.directionX + (y - ray.originY) * ray.directionY + (z - ray.originZ) * ray.directionZ
            val offX = ray.originX + ray.directionX * t - x
            val offY = ray.originY + ray.directionY * t - y
            val offZ = ray.originZ + ray.directionZ * t - z
            assertTrue(t > 0f, "the point ($x, $y, $z) is behind the ray's origin")
            assertNear(0f, kotlin.math.sqrt(offX * offX + offY * offY + offZ * offZ), "the ray misses ($x, $y, $z)")
            assertNear(1f, kotlin.math.sqrt(ray.directionX * ray.directionX + ray.directionY * ray.directionY + ray.directionZ * ray.directionZ), "the direction is not a unit")
        }
    }

    @Test
    fun `the 2D ray is straight down onto the ground point under the pixel`() {
        val camera = EditorCamera(worldWidth = 32f, worldHeight = 18f).apply { fit(WIDTH, HEIGHT) }
        val ground = ViewPoint()
        camera.unproject(200f, 100f, ground)
        val ray = ViewRay()

        camera.ray(200f, 100f, ray)
        assertEquals(listOf(ground.x, ground.y, 0f), listOf(ray.originX, ray.originY, ray.originZ))
        assertEquals(listOf(0f, 0f, -1f), listOf(ray.directionX, ray.directionY, ray.directionZ))
    }

    @Test
    fun `a pixel covers as many world units as a one-pixel step on screen measures, in 2D and at a 3D point's depth`() {
        val flat = EditorCamera(worldWidth = 32f, worldHeight = 18f).apply { fit(WIDTH, HEIGHT) }
        assertNear(1f, flat.unitsPerPixelAt(5f, 5f, 0f) * pixelsPerUnit(flat), "2D, as a share of the measured step")

        val orbit = orbitCamera()
        // At the orbit's centre, one world unit across the view is this many pixels.
        val a = ViewPoint()
        val b = ViewPoint()
        orbit.project(1f, 2f, 0f, a)
        // Straight across the view from -90 yaw: along world X.
        orbit.project(2f, 2f, 0f, b)
        assertNear(1f, orbit.unitsPerPixelAt(1f, 2f, 0f) * (b.x - a.x), "3D at the centre, as a share of the measured step")
        assertTrue(orbit.unitsPerPixelAt(1f, 12f, 0f) > orbit.unitsPerPixelAt(1f, 2f, 0f), "a pixel further away covers no more world")
    }

    private fun orbitCamera(): EditorCamera = EditorCamera().apply {
        dimension = ViewDimension.ThreeD
        targetX = 1f
        targetY = 2f
        targetZ = 0f
        distance = 10f
        fit(WIDTH, HEIGHT)
    }

    /** View pixels one world unit covers, measured through [EditorCamera.project]. */
    private fun pixelsPerUnit(camera: EditorCamera): Float {
        val a = ViewPoint()
        val b = ViewPoint()
        camera.project(0f, 0f, 0f, a)
        camera.project(1f, 0f, 0f, b)
        return b.x - a.x
    }

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < TOLERANCE, "$message: expected $expected, was $actual")
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val TOLERANCE = 0.01f
    }
}
