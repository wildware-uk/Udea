package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.fixtures.QueueingSceneManager
import dev.wildware.udea.core.fixtures.RecordingCueSink
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bodies come back from components, in one pass, in an order two processes agree on.
 *
 * This is spec 3.4's trade taken seriously: because nothing authoritative lives in the solver,
 * a restore may simply throw every body away and build them again. What has to be true for
 * that to be safe is not fidelity — it is **reproducibility**. Same components, same bodies,
 * same creation order, same fixture order, every time and on every machine.
 *
 * What is deliberately *not* asserted anywhere: contact manifolds, warm-start impulses and
 * joint impulses surviving a rebuild. They do not, by design.
 */
class PhysicsRebuildTest {

    /** A body's whole observable state, as a string, so a diff points at the field that moved. */
    private fun describe(physics: NoOpPhysicsWorld, handle: BodyHandle): String {
        val pose = physics.poseOf(handle, BodyPose())
        val velocity = physics.velocityOf(handle, BodyVelocity())
        return "owner=${physics.ownerOf(handle)} pose=$pose vel=$velocity " +
            "awake=${physics.isAwake(handle)} shapes=${physics.shapeOrdersOf(handle)}"
    }

    private fun snapshotOf(physics: SpyPhysicsWorld): List<String> = physics.events.toList()

    @Test
    fun `bodies are created in ascending NetId order whatever order they were spawned in`() {
        val forwards = PhysicsRebuildFixture(bodyCount = 32)
        val backwards = PhysicsRebuildFixture(bodyCount = 32, reverseSpawnOrder = true)

        val a = SpyPhysicsWorld().also { it.rebuildFrom(forwards.world, forwards.netIds) }
        val b = SpyPhysicsWorld().also { it.rebuildFrom(backwards.world, backwards.netIds) }

        assertEquals(
            snapshotOf(a),
            snapshotOf(b),
            "spawn order changed the rebuild order, which would desync two peers",
        )
        val creations = a.events.filter { it.startsWith("create") }
        assertEquals(32, creations.size)
        assertEquals(
            forwards.ids.map { "create $it" },
            creations.map { it.substringBefore(" shapes=") },
            "creation walks ascending NetId",
        )
    }

    @Test
    fun `fixtures are created in shapeOrder, not component-add order`() {
        val fixture = PhysicsRebuildFixture(bodyCount = 4)
        val physics = NoOpPhysicsWorld()
        physics.rebuildFrom(fixture.world, fixture.netIds)

        // Entity 0: Circle added first, then Box, then Capsule. shapeOrder is Box(0),
        // Circle(1), Capsule(2), so that is what the fixtures must be.
        val body = fixture.bodyOf(fixture.ids[0])
        assertEquals(listOf(Box.ORDER, Circle.ORDER, Capsule.ORDER), physics.shapeOrdersOf(body.handle))
    }

    @Test
    fun `rebuilding twice from the same components produces identical bodies`() {
        val fixture = PhysicsRebuildFixture(bodyCount = 64)
        val physics = NoOpPhysicsWorld()

        physics.rebuildFrom(fixture.world, fixture.netIds)
        val first = fixture.ids.map { describe(physics, fixture.bodyOf(it).handle) }

        physics.rebuildFrom(fixture.world, fixture.netIds)
        val second = fixture.ids.map { describe(physics, fixture.bodyOf(it).handle) }

        assertEquals(first, second, "a second restore from the same state must land the same bodies")
        assertEquals(64, physics.bodyCount)
    }

    @Test
    fun `the rebuild is identical across 100 runs`() {
        val fixture = PhysicsRebuildFixture(bodyCount = 24)
        val reference = SpyPhysicsWorld().also { it.rebuildFrom(fixture.world, fixture.netIds) }.events.toList()

        repeat(100) { run ->
            val physics = SpyPhysicsWorld()
            physics.rebuildFrom(fixture.world, fixture.netIds)
            assertEquals(reference, physics.events, "run $run diverged from the first")
        }
    }

    @Test
    fun `stale bodies are destroyed, so the body count matches the components exactly`() {
        val fixture = PhysicsRebuildFixture(bodyCount = 16)
        val physics = NoOpPhysicsWorld()

        // A world that has been running: bodies exist, and some of them belong to entities the
        // snapshot being restored never had.
        repeat(40) { physics.createBody(BodyDef(PhysicsBody(), NetId.NONE, emptyList())) }
        assertEquals(40, physics.bodyCount)

        physics.rebuildFrom(fixture.world, fixture.netIds)

        assertEquals(16, physics.bodyCount, "one body per entity with a PhysicsBody, zero orphans")
        assertEquals(
            16,
            fixture.ids.count { fixture.bodyOf(it).handle.isValid },
            "and every component holds a live handle",
        )
    }

    @Test
    fun `an entity with a body but no NetId is skipped rather than given an arbitrary position`() {
        val fixture = PhysicsRebuildFixture(bodyCount = 4)
        fixture.world.entity { it += PhysicsBody(x = 99f) }

        val physics = NoOpPhysicsWorld()
        physics.rebuildFrom(fixture.world, fixture.netIds)

        assertEquals(
            4,
            physics.bodyCount,
            "an entity with no stable identity has no reproducible place in the order",
        )
    }

    @Test
    fun `a skipped entity's handle is invalidated, not left dangling at a destroyed body`() {
        // The entity the test above skips, in the state it is actually in at runtime: a
        // server-only projectile or a piece of debris (spec 3.4's whole remaining use for a
        // solver) that a system created a body for directly, with no NetId. `rebuildFrom`
        // destroys every body but only rewrites the handles the plan covers, so this one is
        // the entity whose `handle` can outlive the body it names.
        val fixture = PhysicsRebuildFixture(bodyCount = 4)
        val orphan = PhysicsBody(x = 99f)
        fixture.world.entity { it += orphan }
        val physics = NoOpPhysicsWorld()
        orphan.handle = physics.createBody(BodyDef(orphan, NetId.NONE, emptyList()))
        val stale = orphan.handle
        assertTrue(stale.isValid, "the orphan starts out holding a live body")

        physics.rebuildFrom(fixture.world, fixture.netIds)

        assertEquals(
            BodyHandle.NONE,
            orphan.handle,
            "the rebuild destroyed this body, so the component must not still name one: " +
                "TeleportSystem gates on handle.isValid and would throw NoSuchBodyException out " +
                "of onTick, and a backend that recycles indices would alias someone else's body",
        )
        assertFalse(orphan.handle.isValid)
        assertFailsWith<NoSuchBodyException>("the stale handle really does name nothing") {
            physics.poseOf(stale, BodyPose())
        }
        assertEquals(
            4,
            fixture.ids.count { fixture.bodyOf(it).handle.isValid },
            "and clearing every handle first did not clear the planned ones after",
        )
    }

    @Test
    fun `every backend inherits the invalidation, because the shared plan owns it`() {
        // The structural half of the test above: the guarantee has to live in
        // PhysicsRebuildPlan rather than in NoOpPhysicsWorld, or the next backend re-implements
        // the loop and re-introduces the dangling handle. SpyPhysicsWorld is a second
        // implementation writing the same three lines a Box2D backend would.
        val fixture = PhysicsRebuildFixture(bodyCount = 3)
        val orphan = PhysicsBody(x = 7f)
        fixture.world.entity { it += orphan }
        val physics = SpyPhysicsWorld()
        orphan.handle = physics.createBody(BodyDef(orphan, NetId.NONE, emptyList()))

        physics.rebuildFrom(fixture.world, fixture.netIds)

        assertEquals(BodyHandle.NONE, orphan.handle)
        assertEquals(4, PhysicsRebuildPlan.of(fixture.world, fixture.netIds).componentCount)
        assertEquals(3, PhysicsRebuildPlan.of(fixture.world, fixture.netIds).size)
    }

    @Test
    fun `a context built by hand still gets a physics world that rebuilds`() {
        // Guards the seam rather than the algorithm: `SnapshotService.applyNow` calls
        // `ctx.physics.rebuildFrom(world, netIds)`, so every context must have something there.
        val ctx = testGameContext()
        val fixture = PhysicsRebuildFixture(bodyCount = 3)
        ctx.physics.rebuildFrom(fixture.world, fixture.netIds)
        assertEquals(3, ctx.physics.bodyCount)

        val explicit = gameContext {
            rng = testGameContext().rng
            physics = NoOpPhysicsWorld()
            scenes = QueueingSceneManager()
            cues = RecordingCueSink()
        }
        explicit.physics.rebuildFrom(fixture.world, fixture.netIds)
        assertEquals(3, explicit.physics.bodyCount)
    }
}
