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

    /**
     * A world of [bodyCount] entities whose components depend only on their [NetId] index.
     *
     * [reverseSpawnOrder] changes the order the entities are *created* in while leaving the
     * id-to-component mapping identical, which is what makes "spawn order must not leak into
     * the rebuild order" a checkable claim rather than a restatement of the loop.
     */
    private class Fixture(val bodyCount: Int, reverseSpawnOrder: Boolean = false) {
        val netIds = NetIdIndex(capacity = 1024, entityCapacity = 1024)
        val world: World = configureWorld(1024) {}
        val ids: List<NetId> = (0 until bodyCount).map { NetId.of(it, 0) }

        init {
            val order = if (reverseSpawnOrder) (bodyCount - 1) downTo 0 else 0 until bodyCount
            for (index in order) {
                val entity = world.entity {
                    it += PhysicsBody(
                        kind = if (index % 3 == 0) BodyKind.Static else BodyKind.Dynamic,
                        x = index * 1.5f,
                        y = index * -0.25f,
                        angle = index * 0.01f,
                        linearX = index.toFloat(),
                        linearY = -index.toFloat(),
                        angularVelocity = index * 0.5f,
                        awake = index % 5 != 0,
                    )
                    // Added in an order that is deliberately not shapeOrder, so a rebuild that
                    // trusted component-add order would produce the wrong fixture sequence.
                    if (index % 2 == 0) it += Circle(radius = 1f)
                    it += Box(halfWidth = 1f, halfHeight = 2f)
                    if (index % 4 == 0) it += Capsule()
                }
                netIds.bind(entity, ids[index])
            }
        }

        fun bodyOf(id: NetId): PhysicsBody =
            with(world) { checkNotNull(netIds.resolveOrNull(id)) { "$id is not live" }[PhysicsBody] }
    }

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
        val forwards = Fixture(bodyCount = 32)
        val backwards = Fixture(bodyCount = 32, reverseSpawnOrder = true)

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
        val fixture = Fixture(bodyCount = 4)
        val physics = NoOpPhysicsWorld()
        physics.rebuildFrom(fixture.world, fixture.netIds)

        // Entity 0: Circle added first, then Box, then Capsule. shapeOrder is Box(0),
        // Circle(1), Capsule(2), so that is what the fixtures must be.
        val body = fixture.bodyOf(fixture.ids[0])
        assertEquals(listOf(Box.ORDER, Circle.ORDER, Capsule.ORDER), physics.shapeOrdersOf(body.handle))
    }

    @Test
    fun `rebuilding twice from the same components produces identical bodies`() {
        val fixture = Fixture(bodyCount = 64)
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
        val fixture = Fixture(bodyCount = 24)
        val reference = SpyPhysicsWorld().also { it.rebuildFrom(fixture.world, fixture.netIds) }.events.toList()

        repeat(100) { run ->
            val physics = SpyPhysicsWorld()
            physics.rebuildFrom(fixture.world, fixture.netIds)
            assertEquals(reference, physics.events, "run $run diverged from the first")
        }
    }

    @Test
    fun `stale bodies are destroyed, so the body count matches the components exactly`() {
        val fixture = Fixture(bodyCount = 16)
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
        val fixture = Fixture(bodyCount = 4)
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
    fun `rebuilding 500 bodies completes in under 2ms`() {
        val fixture = Fixture(bodyCount = 500)
        val physics = NoOpPhysicsWorld()

        // Warm the JIT: a first-run figure measures interpretation, not the rebuild.
        repeat(20) { physics.rebuildFrom(fixture.world, fixture.netIds) }

        val samples = LongArray(21)
        for (index in samples.indices) {
            val start = System.nanoTime()
            physics.rebuildFrom(fixture.world, fixture.netIds)
            samples[index] = System.nanoTime() - start
        }
        samples.sort()
        val medianNanos = samples[samples.size / 2]

        println("PhysicsRebuildTest: 500 bodies rebuilt in ${medianNanos / 1000}us (median of ${samples.size})")
        assertEquals(500, physics.bodyCount)
        assertTrue(
            medianNanos < 2_000_000L,
            "rebuilding 500 bodies took ${medianNanos / 1000}us, over the 2000us budget",
        )
    }

    @Test
    fun `a context built by hand still gets a physics world that rebuilds`() {
        // Guards the seam rather than the algorithm: `SnapshotService.applyNow` calls
        // `ctx.physics.rebuildFrom(world, netIds)`, so every context must have something there.
        val ctx = testGameContext()
        val fixture = Fixture(bodyCount = 3)
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
