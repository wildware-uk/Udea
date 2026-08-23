package dev.wildware.udea.core.movement

import dev.wildware.udea.core.alloc.AllocationProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `move` allocates nothing. Sixty thousand calls, zero bytes.
 *
 * ## Why the number is sixty thousand
 *
 * Spec 3.4 says "replayable 60x per frame". A thousand movers replayed sixty times is sixty
 * thousand calls in one frame, and at 60Hz a byte allocated per call is 3.6MB per second of pure
 * garbage produced by movement alone - which is a GC pause inside a frame, which at 60Hz is a
 * visible hitch. So the gate is zero and not "small".
 *
 * ## What is deliberately not measured
 *
 * The first `move` against a given [StaticCollision] sizes the mover's [CollisionScratch], and
 * that is one array. It is excluded here by warming up against the same geometry first, and it is
 * asserted separately below so the exclusion is visible rather than convenient: a mover that
 * grew its scratch on *every* call would pass a test that only warmed up, and fails the second
 * test here.
 */
class CharacterMoverAllocationTest {

    private val calls = 60_000

    @Test
    fun `60000 move calls allocate zero bytes`() {
        if (!AllocationProbe.isSupported) {
            // Not a silent skip: a JVM without HotSpot's counters cannot make this measurement at
            // all, and saying so is better than a green tick that measured nothing. CI runs
            // HotSpot, so this branch is not the one that runs in the gate.
            println("[CharacterMoverAllocationTest] thread allocation counters unavailable; not measured")
            return
        }

        val geometry = MoverScenario.geometry()
        val config = MoverScenario.config()
        val mover = CharacterMover()
        val state = MoverScenario.start()
        val intent = MoveIntent()

        // Held outside the measured block: the loop below must not be the thing that first sizes
        // the scratch, and `script` writes into an intent it is handed rather than making one.
        mover.move(state, MoverScenario.script(0, intent), config, geometry, MoverScenario.DT)

        var step = 0
        val bytes = AllocationProbe.bytesAllocated(warmups = 2, attempts = 5) {
            var call = 0
            while (call < calls) {
                MoverScenario.script(step, intent)
                mover.move(state, intent, config, geometry, MoverScenario.DT)
                step++
                call++
            }
        }

        println("[CharacterMoverAllocationTest] $calls move calls allocated $bytes bytes")
        assertEquals(0L, bytes, "move allocated $bytes bytes across $calls calls")
        assertTrue(step >= calls, "the measured block did not run")
    }

    @Test
    fun `a mover sizes its scratch once and never again`() {
        // The one allocation the class admits to, pinned. If `ensureScratch` ever reallocated per
        // call, the test above would still pass with a generous warm-up and this one would not.
        if (!AllocationProbe.isSupported) {
            println("[CharacterMoverAllocationTest] thread allocation counters unavailable; not measured")
            return
        }
        val geometry = MoverScenario.geometry()
        val config = MoverScenario.config()
        val intent = MoveIntent()

        val fresh = CharacterMover()
        val state = MoverScenario.start()
        val firstCall = AllocationProbe.bytesAllocated(warmups = 0, attempts = 1) {
            fresh.move(state, MoverScenario.script(0, intent), config, geometry, MoverScenario.DT)
        }
        assertTrue(
            firstCall > 0L,
            "the first call against new geometry is supposed to size the scratch, and allocated " +
                "$firstCall bytes; if this is now zero the KDoc's admission is stale",
        )

        val later = AllocationProbe.bytesAllocated(warmups = 3, attempts = 5) {
            var call = 0
            while (call < 1000) {
                fresh.move(state, MoverScenario.script(call, intent), config, geometry, MoverScenario.DT)
                call++
            }
        }
        assertEquals(0L, later, "the mover re-sized its scratch after the first call")
    }

    @Test
    fun `a broadphase query allocates nothing`() {
        if (!AllocationProbe.isSupported) {
            println("[CharacterMoverAllocationTest] thread allocation counters unavailable; not measured")
            return
        }
        val geometry = MoverScenario.geometry()
        val scratch = CollisionScratch(geometry.segmentCount)
        var found = 0
        val bytes = AllocationProbe.bytesAllocated {
            var probe = 0
            while (probe < 10_000) {
                found += geometry.query(-1f, -1f, 1f, 1f, scratch)
                probe++
            }
        }
        assertEquals(0L, bytes, "StaticCollision.query allocated $bytes bytes")
        assertTrue(found > 0, "the probe box matched no segments, so nothing was measured")
    }
}
