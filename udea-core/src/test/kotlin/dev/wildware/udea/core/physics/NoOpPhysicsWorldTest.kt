package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The kernel's solverless [PhysicsWorld]: the bookkeeping is real, the simulation is not.
 *
 * That distinction is the whole design of the class, and each half needs asserting separately.
 * A null object that also lost bodies would push "is physics real here?" into every consumer,
 * and a null object that quietly *simulated* would make a dedicated server disagree with a
 * client about where debris landed.
 */
class NoOpPhysicsWorldTest {

    private val owner = NetId.of(3, 0)

    private fun world(): NoOpPhysicsWorld = NoOpPhysicsWorld()

    private fun body(x: Float = 1f, y: Float = 2f, angle: Float = 0.5f) =
        PhysicsBody(x = x, y = y, angle = angle, linearX = 4f, linearY = -5f, angularVelocity = 6f)

    @Test
    fun `a created body is findable by its handle and knows its owner`() {
        val physics = world()

        val handle = physics.createBody(BodyDef(body(), owner, listOf(Box(), Circle())))

        assertTrue(handle.isValid)
        assertEquals(1, physics.bodyCount)
        assertEquals(owner, physics.ownerOf(handle))
        assertEquals(listOf(Box.ORDER, Circle.ORDER), physics.shapeOrdersOf(handle))
        assertEquals(1L, physics.createdCount)
    }

    @Test
    fun `pose and velocity read back what the component described`() {
        val physics = world()
        val handle = physics.createBody(BodyDef(body(x = 7f, y = -8f, angle = 1.25f), owner, emptyList()))

        val pose = physics.poseOf(handle, BodyPose())
        val velocity = physics.velocityOf(handle, BodyVelocity())

        assertEquals(7f, pose.x)
        assertEquals(-8f, pose.y)
        assertEquals(1.25f, pose.angle)
        assertEquals(4f, velocity.linearX)
        assertEquals(-5f, velocity.linearY)
        assertEquals(6f, velocity.angular)
    }

    @Test
    fun `the out-parameter is the object handed back, so a caller may reuse one`() {
        val physics = world()
        val a = physics.createBody(BodyDef(body(x = 1f), owner, emptyList()))
        val b = physics.createBody(BodyDef(body(x = 2f), owner, emptyList()))
        val scratch = BodyPose()

        val first = physics.poseOf(a, scratch)
        assertTrue(first === scratch, "poseOf must fill the caller's object, not allocate one")
        physics.poseOf(b, scratch)
        assertEquals(2f, scratch.x, "and overwrite it on the next read")
    }

    @Test
    fun `a handle that names no body fails loudly rather than reading the origin`() {
        val physics = world()
        val handle = physics.createBody(BodyDef(body(), owner, emptyList()))
        assertTrue(physics.destroyBody(handle))

        assertEquals(NetId.NONE, physics.ownerOf(handle), "a dead handle has no owner")
        assertFailsWith<NoSuchBodyException> { physics.poseOf(handle, BodyPose()) }
        assertFailsWith<NoSuchBodyException> { physics.velocityOf(handle, BodyVelocity()) }
        assertFailsWith<NoSuchBodyException> { physics.teleport(handle, BodyPose()) }
        assertFalse(physics.destroyBody(handle), "a second destroy reports it was already gone")
    }

    @Test
    fun `teleport moves a body and leaves its velocity alone`() {
        val physics = world()
        val handle = physics.createBody(BodyDef(body(), owner, emptyList()))

        physics.teleport(handle, BodyPose().set(40f, 50f, 0.75f))

        val pose = physics.poseOf(handle, BodyPose())
        assertEquals(40f, pose.x)
        assertEquals(50f, pose.y)
        assertEquals(0.75f, pose.angle)
        assertEquals(
            4f,
            physics.velocityOf(handle, BodyVelocity()).linearX,
            "a teleport relocates a thing without re-aiming it",
        )
        assertEquals(1L, physics.teleportCount)
    }

    @Test
    fun `awake is set explicitly and never by the absent solver`() {
        val physics = world()
        val handle = physics.createBody(BodyDef(body(), owner, emptyList()))
        assertTrue(physics.isAwake(handle), "PhysicsBody.awake defaults to true")

        physics.setAwake(handle, false)
        repeat(100) { physics.stepOneTick() }

        assertFalse(physics.isAwake(handle), "stepping must not change any body state")
        assertEquals(1f, physics.poseOf(handle, BodyPose()).x, "and must not integrate anything")
    }

    @Test
    fun `destroyAllBodies empties the world and reports how many there were`() {
        val physics = world()
        repeat(5) { physics.createBody(BodyDef(body(), owner, emptyList())) }

        assertEquals(5, physics.destroyAllBodies())
        assertEquals(0, physics.bodyCount)
        assertEquals(0, physics.destroyAllBodies(), "and a second call has nothing to destroy")
    }

    @Test
    fun `queries find nothing, and leave the caller's buffers in a defined state`() {
        val physics = world()
        physics.createBody(BodyDef(body(), owner, listOf(Box())))
        val buffer = BodyHandleBuffer()
        buffer.add(BodyHandle(99))
        val hit = RayHit(body = BodyHandle(7), pointX = 1f)

        assertEquals(0, physics.overlap(Box(), BodyPose(), buffer))
        assertEquals(0, buffer.size, "overlap must clear the buffer even when it finds nothing")

        assertFalse(physics.raycast(0f, 0f, 10f, 10f, hit))
        assertEquals(BodyHandle(7), hit.body, "a miss leaves the hit untouched, which is why the")
        assertEquals(1f, hit.pointX, "return value is the thing a caller must read")
    }

    @Test
    fun `a contact listener may be registered and removed even though it never fires`() {
        val physics = world()
        val listener = object : ContactListener {
            override fun onBeginContact(a: BodyHandle, b: BodyHandle) = error("no solver, no contacts")

            override fun onEndContact(a: BodyHandle, b: BodyHandle) = error("no solver, no contacts")
        }

        physics.addContactListener(listener)
        physics.addContactListener(listener)
        assertEquals(1, physics.contactListenerCount, "registering twice is a no-op")

        repeat(50) { physics.stepOneTick() }

        assertTrue(physics.removeContactListener(listener))
        assertFalse(physics.removeContactListener(listener), "and removing twice reports it was gone")
    }

    @Test
    fun `a handle buffer grows, hands back typed handles and refuses an index it does not hold`() {
        val buffer = BodyHandleBuffer(initialCapacity = 2)

        repeat(9) { buffer.add(BodyHandle(it * 10)) }

        assertEquals(9, buffer.size)
        assertEquals(BodyHandle(80), buffer[8], "the buffer grew rather than dropping entries")
        assertFailsWith<IllegalArgumentException> { buffer[9] }

        buffer.clear()
        assertEquals(0, buffer.size)
        assertFailsWith<IllegalArgumentException> { buffer[0] }
    }

    @Test
    fun `a chain must be a whole number of points`() {
        assertEquals(3, Chain(floatArrayOf(0f, 0f, 1f, 1f, 2f, 0f)).pointCount)
        val failure = assertFailsWith<IllegalArgumentException> { Chain(floatArrayOf(0f, 0f, 1f)) }
        assertTrue("even" in failure.message.orEmpty(), "${failure.message}")
    }

    @Test
    fun `a rebuild plan records the entity and id behind every body it will create`() {
        val fixtureWorld = configureWorld(64) {}
        val netIds = NetIdIndex(64, 64)
        val entity = fixtureWorld.entity {
            it += PhysicsBody(x = 5f)
            it += Chain(floatArrayOf(0f, 0f, 1f, 1f))
        }
        val id = netIds.allocate(entity)

        val plan = PhysicsRebuildPlan.of(fixtureWorld, netIds)

        assertEquals(1, plan.size)
        val planned = plan.bodies.single()
        assertEquals(id, planned.netId, "a backend needs the id to tag the body it creates")
        assertEquals(entity, planned.entity)
        assertEquals(5f, planned.component.x)
        assertEquals(listOf(Chain.ORDER), planned.def.shapes.map { it.shapeOrder })
    }
}
