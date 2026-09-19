package dev.wildware.udea.physics2d

import dev.wildware.udea.core.physics.BodyHandle
import dev.wildware.udea.core.physics.BodyHandleBuffer
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.BodyPose
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Capsule
import dev.wildware.udea.core.physics.Chain
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.ContactListener
import dev.wildware.udea.core.physics.NoSuchBodyException
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.physics.RayHit
import dev.wildware.udea.core.physics.Teleport
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The `PhysicsWorld` contract, held by a real Box2D solver. */
class Box2DPhysicsWorldTest {

    @Test
    fun `one tick is one fixed step of 1 over tickRate seconds with the configured sub-steps`() {
        // A free body under gravity, integrated by Box2D's semi-implicit Euler in n sub-steps of
        // h = dt / n, is at y = g h^2 n(n+1)/2 after one tick. Different tick rates and sub-step
        // counts give different, predictable answers - which is what makes the step length the
        // tick's and the sub-steps the setting's, rather than anything read off a clock.
        for ((tickRate, subSteps) in listOf(60 to 4, 30 to 4, 60 to 1, 60 to 8)) {
            Box2DScene(Physics2DSettings(gravityY = -10f, subSteps = subSteps), tickRate = tickRate).use { scene ->
                val id = scene.spawn(PhysicsBody(), Circle(0.5f))
                scene.step()

                val h = 1.0 / tickRate / subSteps
                val expected = -10.0 * h * h * subSteps * (subSteps + 1) / 2.0
                val actual = scene.bodyOf(id).y.toDouble()
                assertTrue(
                    abs(actual - expected) <= abs(expected) * 1e-5,
                    "tickRate=$tickRate subSteps=$subSteps: y=$actual, closed form $expected",
                )
            }
        }
    }

    @Test
    fun `the world is opened with the configured sleep and continuous settings`() {
        Box2DScene(Physics2DSettings(enableSleep = false, enableContinuous = false)).use { scene ->
            assertEquals(Box2DPhysicsWorld.SolverFlags(continuous = false, sleeping = false), scene.physics.solverFlags())
        }
        Box2DScene(Physics2DSettings(enableSleep = true, enableContinuous = true)).use { scene ->
            assertEquals(Box2DPhysicsWorld.SolverFlags(continuous = true, sleeping = true), scene.physics.solverFlags())
        }
    }

    @Test
    fun `the native library is Box2D 3_1_1`() {
        // Pinned because the bindings' README names another version: this is the one that runs.
        Box2DScene().use { scene -> assertEquals("3.1.1", scene.physics.box2DVersion()) }
    }

    @Test
    fun `the solved pose and velocity are copied into PhysicsBody every tick`() {
        Box2DScene().use { scene ->
            val id = scene.spawn(PhysicsBody(x = 1f, y = 10f, linearX = 2f), Circle(0.5f))
            repeat(30) { scene.step() }

            val body = scene.bodyOf(id)
            val pose = scene.physics.poseOf(body.handle, BodyPose())
            assertEquals(pose.x, body.x)
            assertEquals(pose.y, body.y)
            assertTrue(body.x > 1.9f && body.y < 9f, "moved right and fell: $body")
            assertTrue(body.linearY < 0f, "falling velocity was written back: ${body.linearY}")
        }
    }

    @Test
    fun `a kinematic body moves at its velocity and ignores gravity`() {
        Box2DScene().use { scene ->
            val id = scene.spawn(PhysicsBody(kind = BodyKind.Kinematic, linearX = 3f), Box(1f, 1f))
            repeat(60) { scene.step() }
            assertEquals(0f, scene.bodyOf(id).y)
            assertTrue(abs(scene.bodyOf(id).x - 3f) < 1e-4f, "one second at 3 units a second: ${scene.bodyOf(id).x}")
        }
    }

    @Test
    fun `landing on the floor begins a contact and leaving it ends one`() {
        Box2DScene().use { scene ->
            val floor = scene.spawn(PhysicsBody(kind = BodyKind.Static, y = -0.5f), Box(10f, 0.5f))
            val ball = scene.spawn(PhysicsBody(y = 2f), Circle(0.5f))
            val events = RecordingListener().also(scene.physics::addContactListener)

            repeat(60) { scene.step() }
            val pair = setOf(scene.bodyOf(floor).handle, scene.bodyOf(ball).handle)
            assertEquals(listOf(pair), events.begins, "one begin, naming the floor and the ball")
            assertTrue(events.ends.isEmpty(), "still touching")

            with(scene.world) { scene.entityOf(ball).configure { it += Teleport(x = 0f, y = 5f) } }
            scene.step()
            assertEquals(listOf(pair), events.ends, "one end once the ball is lifted clear")
        }
    }

    @Test
    fun `a sensor reports a body passing through it and never stops it`() {
        Box2DScene().use { scene ->
            val sensor = scene.spawn(PhysicsBody(kind = BodyKind.Static, y = 5f, isSensor = true), Box(3f, 1f))
            val ball = scene.spawn(PhysicsBody(y = 10f), Circle(0.3f))
            val events = RecordingListener().also(scene.physics::addContactListener)

            repeat(120) { scene.step() }

            val pair = setOf(scene.bodyOf(sensor).handle, scene.bodyOf(ball).handle)
            assertEquals(listOf(pair), events.begins, "entered the sensor once")
            assertEquals(listOf(pair), events.ends, "left it once")
            assertTrue(scene.bodyOf(ball).y < 3f, "fell straight through: ${scene.bodyOf(ball).y}")
        }
    }

    @Test
    fun `a raycast reports the nearest body, where it was hit and the surface normal`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            scene.spawn(PhysicsBody(kind = BodyKind.Static), Box(10f, 0.5f))
            val ball = scene.spawn(PhysicsBody(y = 3f), Circle(0.5f))
            scene.step()
            val hit = RayHit()

            assertTrue(scene.physics.raycast(0f, 10f, 0f, -10f, hit))
            assertEquals(scene.bodyOf(ball).handle, hit.body, "the ball is nearer than the floor")
            assertTrue(abs(hit.pointY - 3.5f) < 1e-4f, "hit the top of the ball: ${hit.pointY}")
            assertTrue(abs(hit.normalY - 1f) < 1e-4f, "facing up: ${hit.normalY}")
            assertTrue(abs(hit.fraction - 0.325f) < 1e-4f, "6.5 of 20 units along: ${hit.fraction}")

            assertFalse(scene.physics.raycast(50f, 10f, 50f, -10f, hit), "nothing out there")
        }
    }

    @Test
    fun `an overlap query finds every body it touches once, and a chain is not a query shape`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            // Two shapes on one body must still report the body once.
            val twoShapes = scene.spawn(PhysicsBody(x = 0f), Box(0.5f, 0.5f), Circle(0.6f))
            val other = scene.spawn(PhysicsBody(x = 1.5f), Capsule(0.3f, 0.5f))
            scene.spawn(PhysicsBody(x = 20f), Circle(0.5f))
            scene.step()
            val out = BodyHandleBuffer()

            val found = scene.physics.overlap(Box(1.2f, 0.2f), BodyPose(0.5f, 0f, 0f), out)

            assertEquals(2, found)
            assertEquals(
                setOf(scene.bodyOf(twoShapes).handle, scene.bodyOf(other).handle),
                (0 until out.size).map { out[it] }.toSet(),
            )
            // Rotated a quarter turn the long thin box points up and misses the capsule.
            assertEquals(1, scene.physics.overlap(Box(1.2f, 0.2f), BodyPose(0f, 0f, HALF_PI), out))
            assertFailsWith<IllegalArgumentException> {
                scene.physics.overlap(Chain(floatArrayOf(0f, 0f, 1f, 1f)), BodyPose(), out)
            }
        }
    }

    @Test
    fun `a Teleport component moves the body once and the solver carries on from there`() {
        Box2DScene(Physics2DSettings(gravityY = 0f)).use { scene ->
            val id = scene.spawn(PhysicsBody(linearX = 1f), Circle(0.5f))
            scene.step()
            with(scene.world) { scene.entityOf(id).configure { it += Teleport(x = 40f, y = 7f) } }
            scene.step()

            val body = scene.bodyOf(id)
            assertTrue(abs(body.x - (40f + 1f / 60f)) < 1e-4f && body.y == 7f, "teleported, then moved one tick: $body")
            assertEquals(1f, body.linearX, "a teleport relocates without re-aiming")
        }
    }

    @Test
    fun `setAwake puts a body to sleep and the component says so`() {
        Box2DScene().use { scene ->
            val id = scene.spawn(PhysicsBody(y = 10f), Circle(0.5f))
            scene.step()
            scene.physics.setAwake(scene.bodyOf(id).handle, false)
            val y = scene.bodyOf(id).y
            scene.step()

            assertFalse(scene.bodyOf(id).awake)
            assertEquals(y, scene.bodyOf(id).y, "an asleep body does not fall")
        }
    }

    @Test
    fun `editing a shape component rebuilds the body from the new shape`() {
        Box2DScene().use { scene ->
            scene.spawn(PhysicsBody(kind = BodyKind.Static, y = -0.5f), Box(10f, 0.5f))
            val ball = scene.spawn(PhysicsBody(y = 1f), Circle(0.5f))
            repeat(120) { scene.step() }
            val before = scene.bodyOf(ball).handle
            assertTrue(abs(scene.bodyOf(ball).y - 0.5f) < 0.02f, "resting at its radius: ${scene.bodyOf(ball).y}")

            with(scene.world) { scene.entityOf(ball)[Circle].radius = 2f }
            repeat(120) { scene.step() }

            assertNotEquals(before, scene.bodyOf(ball).handle, "a new body")
            assertEquals(2, scene.physics.bodyCount, "and the old one is gone")
            assertTrue(abs(scene.bodyOf(ball).y - 2f) < 0.05f, "resting at its new radius: ${scene.bodyOf(ball).y}")
        }
    }

    @Test
    fun `a body that is gone cannot be read`() {
        Box2DScene().use { scene ->
            val id = scene.spawn(PhysicsBody(), Circle(0.5f))
            scene.step()
            val handle = scene.bodyOf(id).handle
            assertTrue(scene.physics.destroyBody(handle))

            assertFalse(scene.physics.destroyBody(handle), "already gone")
            assertEquals(dev.wildware.udea.core.identity.NetId.NONE, scene.physics.ownerOf(handle))
            assertFailsWith<NoSuchBodyException> { scene.physics.poseOf(handle, BodyPose()) }
        }
    }

    @Test
    fun `a PhysicsBody with no NetId fails loudly instead of silently never moving`() {
        Box2DScene().use { scene ->
            scene.world.entity { it += PhysicsBody(y = 5f); it += Circle(0.5f) }
            val failure = assertFailsWith<IllegalStateException> { scene.step() }
            assertTrue("has no NetId" in failure.message.orEmpty(), failure.message)
        }
    }

    /** Records each begin and end as the unordered pair it names. */
    private class RecordingListener : ContactListener {
        val begins = ArrayList<Set<BodyHandle>>()
        val ends = ArrayList<Set<BodyHandle>>()

        override fun onBeginContact(a: BodyHandle, b: BodyHandle) {
            begins += setOf(a, b)
        }

        override fun onEndContact(a: BodyHandle, b: BodyHandle) {
            ends += setOf(a, b)
        }
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2).toFloat()
    }
}
