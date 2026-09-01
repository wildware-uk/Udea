package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.alloc.AllocationProbe
import dev.wildware.udea.diagnostics.bench.LatencyBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Phase 0 capture budgets, enforced.
 *
 * Spec 6's Phase 0 exit and spec 7's risk row both state these as hard CI gates: capture under
 * 1ms at 1000 entities, allocation-free, ring under 64MB. The reason is stated outright — one
 * structure carries time travel, replication baselines and rollback, so if capture allocates
 * then three features degrade at once, and a budget nobody enforces is missed silently
 * somewhere around Phase 3.
 *
 * Every number comes from [SnapshotBudgets], so moving one is a reviewed diff rather than an
 * edit buried in a test. The documented remedy when this fails on slower hardware is
 * [SnapshotRing.degrade] — never a looser budget and never a disabled task.
 *
 * `System.nanoTime` appears here and nowhere else in the tree: measuring wall time is what a
 * benchmark is for, and the ban on wall clocks is a ban on reading one *inside*
 * `Simulation.step()`.
 */
class SnapshotBudgetTest {

    @Test
    fun `the median capture of a thousand entities is inside its one millisecond budget`() {
        LatencyBudget.measuredBy(":udea-core:udeaSnapshotBudget")

        val sim = SnapshotWorld(idCapacity = SnapshotBudgets.CAPTURE_ENTITIES * 2)
        sim.spawn(SnapshotBudgets.CAPTURE_ENTITIES)
        repeat(30) { sim.step() }
        val slot = sim.service.newSnapshot()

        repeat(WARMUP_CAPTURES) { sim.service.captureInto(slot) }

        val samples = LongArray(MEASURED_CAPTURES)
        for (index in samples.indices) {
            val before = System.nanoTime()
            sim.service.captureInto(slot)
            samples[index] = System.nanoTime() - before
        }
        samples.sort()
        val median = samples[samples.size / 2]

        println(
            "udeaSnapshotBudget: capture of ${SnapshotBudgets.CAPTURE_ENTITIES} entities " +
                "median ${median}ns, p95 ${samples[(samples.size * 95) / 100]}ns, " +
                "budget ${SnapshotBudgets.CAPTURE_NANOS}ns",
        )
        assertTrue(
            median <= SnapshotBudgets.CAPTURE_NANOS,
            "capture of ${SnapshotBudgets.CAPTURE_ENTITIES} entities took a median of ${median}ns " +
                "against a ${SnapshotBudgets.CAPTURE_NANOS}ns budget. Do not loosen this number: " +
                "raise sparseInterval with SnapshotRing.degrade() instead (spec 7). " +
                LatencyBudget.contentionNote(":udea-core:udeaSnapshotBudget"),
        )
    }

    @Test
    fun `a warm capture of a thousand entities allocates exactly zero bytes`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")
        val sim = SnapshotWorld(idCapacity = SnapshotBudgets.CAPTURE_ENTITIES * 2)
        sim.spawn(SnapshotBudgets.CAPTURE_ENTITIES)
        val slot = sim.service.newSnapshot()
        repeat(WARMUP_CAPTURES) { sim.service.captureInto(slot) }

        val allocated = AllocationProbe.bytesAllocated { sim.service.captureInto(slot) }

        println("udeaSnapshotBudget: warm capture allocated ${allocated} bytes")
        assertEquals(
            SnapshotBudgets.CAPTURE_ALLOCATED_BYTES,
            allocated,
            "a warm capture allocated $allocated bytes. At 60Hz that is $allocated bytes a tick " +
                "of garbage on the path three features share.",
        )
    }

    @Test
    fun `the ring stays inside its sixty-four megabyte budget over a full sparse window`() {
        val sim = SnapshotWorld(idCapacity = SnapshotBudgets.CAPTURE_ENTITIES * 2)
        sim.spawn(SnapshotBudgets.CAPTURE_ENTITIES)

        repeat(RingConfig.DEFAULT_SPARSE_WINDOW_TICKS) {
            sim.step()
            val slot = sim.ring.acquire()
            sim.service.captureInto(slot)
            sim.ring.commit(slot)
            assertTrue(
                sim.ring.totalBytes <= SnapshotBudgets.RING_BYTES,
                "the ring reached ${sim.ring.totalBytes} bytes at tick ${sim.tick}, over its " +
                    "${SnapshotBudgets.RING_BYTES}-byte budget",
            )
        }

        println(
            "udeaSnapshotBudget: ring held ${sim.ring.size} slots, ${sim.ring.totalBytes} bytes " +
                "of ${SnapshotBudgets.RING_BYTES}, sparseInterval ${sim.ring.sparseInterval} " +
                "after ${sim.ring.degradeCount} degrade(s)",
        )
        // Whether it degraded or not, the sixty-second window is still spanned. That is the
        // policy spec 7 fixes: density is what is spent under pressure, never reach.
        val span = sim.ring.newestTick()!!.ticksSince(sim.ring.oldestTick()!!)
        assertTrue(
            span >= RingConfig.DEFAULT_SPARSE_WINDOW_TICKS - sim.ring.sparseInterval,
            "the rewind window collapsed to $span ticks",
        )
        assertTrue(sim.ring.slotCount < RingConfig.DEFAULT_SPARSE_WINDOW_TICKS, "slots must be pooled")
    }

    private companion object {
        /** Enough for the JIT to compile the capture loop; the engine only ever runs warm. */
        const val WARMUP_CAPTURES: Int = 200

        /** Odd count so the median is a sample rather than a mean of two. */
        const val MEASURED_CAPTURES: Int = 101
    }
}
