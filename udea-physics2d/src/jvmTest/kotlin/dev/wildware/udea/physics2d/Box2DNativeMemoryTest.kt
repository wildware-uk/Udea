package dev.wildware.udea.physics2d

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Chain
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.physics2d.Box2DScene.Companion.buildStandardScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Box2D bodies and worlds are native memory, and destroying them frees it.
 *
 * Counted, never timed. Three tallies, and none of them is this module's opinion of itself:
 * `b2World_GetCounters` is Box2D's count of the bodies, shapes and contacts alive in a world;
 * `b2GetByteCount` is Box2D's count of every byte it has allocated in the process; and
 * `liveNativeStructs` counts the scratch structs a world allocates through the bindings. A leak is
 * a number that does not come back down.
 *
 * Bytes are compared only across a whole world's life. Inside a live world Box2D keeps its arrays
 * at their high-water mark and grows them when contacts arrive in a new order, so its byte count
 * steps up and plateaus across identical cycles - measured at 553564 to 565708 bytes over forty
 * cycles of 150 bodies, flat from the thirtieth - and a byte-equality check per cycle would call
 * that capacity a leak. The live-object counts are exact at every cycle instead.
 */
class Box2DNativeMemoryTest {

    @Test
    fun `creating and destroying bodies over and over leaves Box2D holding nothing`() {
        Box2DScene().use { scene ->
            repeat(CYCLES) { cycle ->
                val peak = spawnAndDespawn(scene)
                assertEquals(
                    Box2DPhysicsWorld.SolverCounts(bodies = BODIES, shapes = SHAPES, contacts = peak.contacts),
                    peak,
                    "cycle $cycle: every spawned body and shape was alive in Box2D",
                )
                assertTrue(peak.contacts > 0, "cycle $cycle: the bodies touched, so contacts were made and must be freed")
                assertEquals(
                    Box2DPhysicsWorld.SolverCounts(bodies = 0, shapes = 0, contacts = 0),
                    scene.physics.solverCounts(),
                    "cycle $cycle: what Box2D still holds after every entity was despawned",
                )
            }
        }
    }

    @Test
    fun `closing a world frees everything it allocated`() {
        // Loads the natives and settles Box2D's process-wide state before the baseline is read.
        Box2DScene().use { it.run(1) }
        val baseline = box2DAllocatedBytes()

        repeat(WORLDS) { round ->
            val scene = Box2DScene()
            scene.buildStandardScene()
            scene.run(60)
            assertTrue(box2DAllocatedBytes() > baseline, "round $round: an open world holds Box2D memory")
            assertTrue(scene.physics.liveNativeStructs > 0, "round $round: an open world holds scratch structs")

            scene.close()

            assertEquals(baseline, box2DAllocatedBytes(), "round $round: Box2D memory after close")
            assertEquals(0, scene.physics.liveNativeStructs, "round $round: scratch structs after close")
        }
    }

    @Test
    fun `a rebuild frees the world it replaces`() {
        Box2DScene().use { scene ->
            scene.buildStandardScene()
            scene.run(30)
            val live = scene.physics.solverCounts()
            // A rebuild opens a fresh world, which starts without the contact graph; everything
            // else is freed with the old world, so each rebuild lands on exactly the same bytes.
            scene.physics.rebuildFrom(scene.world, scene.netIds)
            val afterFirstRebuild = box2DAllocatedBytes()
            repeat(CYCLES) { scene.physics.rebuildFrom(scene.world, scene.netIds) }

            assertEquals(afterFirstRebuild, box2DAllocatedBytes(), "Box2D memory across $CYCLES rebuilds")
            assertEquals(live.copy(contacts = 0), scene.physics.solverCounts(), "the same bodies and shapes, once")
        }
    }

    /**
     * Spawns [BODIES] bodies of every shape, steps them into contact, then despawns them all.
     *
     * Two ways out, because a body leaves in two ways: the box and circle entities are destroyed
     * and the reconcile frees their bodies on the next tick; the chain entities, which may not be
     * destroyed mid-scene, go the way scene teardown takes them - `destroyAllBodies` first.
     */
    private fun spawnAndDespawn(scene: Box2DScene): Box2DPhysicsWorld.SolverCounts {
        val dynamic = ArrayList<NetId>(BODIES)
        val chains = ArrayList<NetId>(BODIES)
        repeat(BODIES) { index ->
            val body = PhysicsBody(x = (index % 20) * 0.9f, y = 1f + index / 20)
            when (index % 3) {
                0 -> dynamic += scene.spawn(body, Box(0.5f, 0.5f))
                1 -> dynamic += scene.spawn(body, Circle(0.5f))
                else -> chains += scene.spawn(body, Chain(floatArrayOf(0f, 0f, 1f, 0.5f, 2f, 0f)))
            }
        }
        repeat(10) { scene.step() }
        assertEquals(BODIES, scene.physics.bodyCount)
        val peak = scene.physics.solverCounts()

        despawn(scene, dynamic)
        scene.step()
        assertEquals(chains.size, scene.physics.bodyCount, "the reconcile freed every destroyed entity's body")

        scene.physics.destroyAllBodies()
        despawn(scene, chains)
        scene.step()
        assertEquals(0, scene.physics.bodyCount)
        return peak
    }

    private fun despawn(scene: Box2DScene, ids: List<NetId>) {
        for (id in ids) {
            scene.world -= scene.entityOf(id)
            scene.netIds.free(id)
        }
    }

    private companion object {
        const val BODIES = 150

        /** One shape per box or circle body, and two segments per three-vertex chain. */
        const val SHAPES = BODIES / 3 * 2 + BODIES / 3 * 2
        const val CYCLES = 40
        const val WORLDS = 20
    }
}
