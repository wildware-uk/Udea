package dev.wildware.udea.core.physics

import dev.wildware.udea.diagnostics.bench.LatencyBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rebuilding 500 bodies from their components costs less than 2ms.
 *
 * ## What the number is for
 *
 * Spec 3.4 trades fidelity for reproducibility: nothing authoritative lives in the solver, so a
 * snapshot restore is allowed to throw every body away and build them again. That is only a
 * sensible trade while building them again is cheap, and `SnapshotService.applyNow` calls
 * `ctx.physics.rebuildFrom(world, netIds)` on a path a 60Hz tick is waiting on. 2ms is an eighth
 * of a frame.
 *
 * ## Why it is not in `PhysicsRebuildTest` any more (issue #182)
 *
 * It was, and `PhysicsRebuildTest` runs inside `:udea-core:test`, which is on `check`, which is on
 * `build`. So a 2ms line was being read while nineteen modules compiled on the same cores - the
 * defect issue #175 exists to remove, in a gate neither #175 nor #182 had listed. It survived
 * because it never failed, which was luck: 2ms is a tight line and this repository has measured
 * the same code two to four times slower inside a parallel build than alone.
 *
 * Everything else `PhysicsRebuildTest` asserts - that the same components give the same bodies in
 * the same creation and fixture order, on any machine - is untouched and still on `check`. This
 * is the only claim in it that a busy machine can change the answer to.
 *
 * ## The estimator
 *
 * Median of 21 timed rebuilds after 20 warm-up rebuilds, unchanged by the move. The median is
 * right here for the reason #175 gave when it made `DaemonLatencyBudgetTest`'s reload gate a
 * median: this is a steady-state cost paid on every restore, so the typical rebuild is the
 * subject, and the maximum of a sample would be the worst scheduling hiccup in the window.
 *
 * If it fails, the remedy is what `rebuildFrom` does per body - never a wider budget.
 */
class PhysicsRebuildBudgetTest {

    @Test
    fun `rebuilding 500 bodies completes in under 2ms`() {
        LatencyBudget.measuredBy(TASK)

        val fixture = PhysicsRebuildFixture(bodyCount = 500)
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

        println(
            "PhysicsRebuildBudgetTest: 500 bodies rebuilt in ${medianNanos / 1000}us " +
                "(median of ${samples.size}, best ${samples.first() / 1000}us, " +
                "worst ${samples.last() / 1000}us)",
        )
        // The control: a rebuild that produced no bodies would come in under any budget.
        assertEquals(500, physics.bodyCount)
        assertTrue(
            medianNanos < BUDGET_NANOS,
            "rebuilding 500 bodies took ${medianNanos / 1000}us, over the " +
                "${BUDGET_NANOS / 1000}us budget. " + LatencyBudget.contentionNote(TASK),
        )
    }

    private companion object {

        /** The task that measures this, and the one to re-run alone before believing a red. */
        const val TASK = ":udea-core:udeaPhysicsRebuildBudget"

        /** An eighth of a 60Hz frame. See the class KDoc before changing it. */
        const val BUDGET_NANOS = 2_000_000L
    }
}
