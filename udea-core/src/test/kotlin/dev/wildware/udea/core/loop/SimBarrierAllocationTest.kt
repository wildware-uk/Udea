package dev.wildware.udea.core.loop

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.alloc.AllocationProbe
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/** Touches one counter and nothing else, so the measurement is of the drain and not of it. */
private class CountingAction(override val label: String) : BarrierAction {
    var applied: Int = 0
        private set

    override fun apply(world: World, ctx: GameContext) {
        applied++
    }
}

/**
 * The drain runs before every single tick, so it may not allocate.
 *
 * A per-tick allocation is not a leak — it is a GC pause with a 60Hz metronome behind it.
 * The design that buys zero here is two pooled lists swapped under the lock and an
 * index-walked batch: no iterator object, no fresh queue, no boxing. This test is what stops
 * a later refactor to `ConcurrentLinkedQueue` or a `forEach` from quietly reintroducing it.
 */
class SimBarrierAllocationTest {

    @Test
    fun `600 ticks of a 10-actions-per-tick load allocates nothing in the drain path`() {
        assumeTrue(AllocationProbe.isSupported, "HotSpot thread allocation counters required")

        val ctx = testGameContext()
        val world = configureWorld { injectables { gameContext(ctx) } }
        val barrier = SimBarrier()
        // Pre-allocated: the actions themselves are the submitter's cost, not the barrier's.
        val actions = Array(ACTIONS_PER_TICK) { CountingAction("action-$it") }

        val load = {
            repeat(TICKS) {
                for (index in actions.indices) barrier.submit(actions[index])
                barrier.drain(world, ctx)
            }
        }

        val bytes = AllocationProbe.bytesAllocated(block = load)

        assertEquals(
            0L,
            bytes,
            "the drain allocated $bytes bytes over $TICKS ticks; steady state must be zero",
        )
        assertEquals(0, barrier.pendingCount())
        assertEquals(ACTIONS_PER_TICK, barrier.drainedThisTick)
        assertEquals(0L, barrier.failedActions)
    }

    private companion object {
        const val TICKS = 600
        const val ACTIONS_PER_TICK = 10
    }
}
