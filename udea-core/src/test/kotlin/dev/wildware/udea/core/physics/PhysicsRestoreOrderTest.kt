package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.snapshot.Movement
import dev.wildware.udea.core.snapshot.SnapshotWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rebuild is the **last** stage of a restore, after every `Replicator.apply`.
 *
 * It has to be, and the reason is the whole point of demoting Box2D: bodies are rebuilt *from
 * components*, so a rebuild that ran before the components were restored would faithfully
 * recreate the world as it was a moment before the rewind — plausible, wrong, and invisible.
 *
 * A counter cannot see this. The physics world here reads a restored component at the moment
 * it is asked to rebuild, and the test asserts it saw the restored value rather than the live
 * one.
 */
class PhysicsRestoreOrderTest {

    /** Records what the world looked like at the instant `rebuildFrom` was called. */
    private class ObservingPhysicsWorld(
        private val delegate: NoOpPhysicsWorld = NoOpPhysicsWorld(),
    ) : PhysicsWorld by delegate {

        val observedPositions = ArrayList<Float>()

        override fun rebuildFrom(world: World, netIds: NetIdIndex) {
            netIds.forEachLive { _, entity ->
                with(world) { entity.getOrNull(Movement) }?.let { observedPositions += it.position.x }
            }
            delegate.rebuildFrom(world, netIds)
        }
    }

    @Test
    fun `rebuildFrom sees the restored component values, not the pre-restore ones`() {
        val physics = ObservingPhysicsWorld()
        val sim = SnapshotWorld(physicsWorld = physics)
        val ids = sim.spawn(4)

        val captured = sim.service.capture()
        val positionsAtCapture = ids.map { sim.positionXOf(it) }

        // Run the world well away from the captured state.
        repeat(120) { sim.step() }
        val positionsBeforeRestore = ids.map { sim.positionXOf(it) }
        assertTrue(
            positionsBeforeRestore != positionsAtCapture,
            "the world must actually have moved, or this test cannot fail",
        )

        physics.observedPositions.clear()
        sim.service.applyNow(captured)

        assertEquals(
            positionsAtCapture,
            physics.observedPositions,
            "the rebuild ran before the fields were applied, so it saw the pre-restore world",
        )
    }
}
