package dev.wildware.udea.core.rng

import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.alloc.AllocationProbe
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A draw is a hot-path operation, so it may not allocate.
 *
 * Randomness in this engine is not occasional: a wave spawn, a crit roll and an AI decision
 * can each happen thousands of times inside one 16ms tick. A generator that allocated per
 * call would turn that into GC pressure with a 60Hz metronome behind it — which is also why
 * `stream()` returns a stable instance rather than constructing one per call.
 */
class RngAllocationTest {

    @Test
    fun `one hundred thousand nextFloat calls allocate nothing`() {
        assumeTrue(AllocationProbe.isSupported, "HotSpot thread allocation counters required")

        val random = SimRandom(seed = 1L)
        var sink = 0f

        val bytes = AllocationProbe.bytesAllocated {
            var total = 0f
            repeat(DRAWS) { total += random.nextFloat() }
            sink = total
        }

        assertEquals(0L, bytes, "nextFloat allocated $bytes bytes over $DRAWS draws")
        // Consumes the result so the loop cannot be optimised away entirely.
        assertTrue(sink > 0f, "the draws must actually have happened")
    }

    @Test
    fun `drawing through the service allocates nothing either`() {
        assumeTrue(AllocationProbe.isSupported, "HotSpot thread allocation counters required")

        val service = DefaultRngService(1L)
        var sink = 0L

        val bytes = AllocationProbe.bytesAllocated {
            var total = 0L
            repeat(DRAWS) {
                total += service.nextInt(RngStream.Combat, 100).toLong()
                total += service.stream(RngStream.Loot).nextLong()
            }
            sink = total
        }

        assertEquals(0L, bytes, "the service allocated $bytes bytes over $DRAWS draws")
        assertTrue(sink != 0L)
    }

    private companion object {
        const val DRAWS = 100_000
    }
}
