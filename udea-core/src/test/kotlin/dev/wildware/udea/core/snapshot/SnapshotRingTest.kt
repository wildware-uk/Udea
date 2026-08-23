package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Log
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.alloc.AllocationProbe
import dev.wildware.udea.core.loop.SnapshotKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * One ring, two cadences.
 *
 * Spec 7 requires both windows out of a single structure — 2s dense for rollback, 60s sparse
 * for the agent's rewind — because if capture allocates then time travel, replication baselines
 * and rollback degrade at once. Every test here is about a slot being *the same object* moving
 * between windows and back to the pool, never a second store or a fresh allocation.
 */
class SnapshotRingTest {

    private val registry = TestComponents.registry()

    @Test
    fun `after a full sparse window the ring holds every dense tick and every keyframe, and nothing older`() {
        val ring = SnapshotRing(registry)
        fill(ring, TICKS)

        val newest = Tick(TICKS.toLong())
        val held = ring.listSnapshots()
        val ticks = held.map { it.tick.value }.toSet()

        for (tick in (TICKS - RingConfig.DEFAULT_DENSE_TICKS + 1)..TICKS) {
            assertTrue(tick.toLong() in ticks, "the dense window is missing tick $tick")
        }
        // The ring was filled from tick 1, so tick 0 was never offered to it.
        val sparseStart = maxOf(1, TICKS - RingConfig.DEFAULT_SPARSE_WINDOW_TICKS)
        for (tick in sparseStart..(TICKS - RingConfig.DEFAULT_DENSE_TICKS)) {
            val expected = tick % RingConfig.DEFAULT_SPARSE_INTERVAL == 0
            assertEquals(
                expected,
                tick.toLong() in ticks,
                "tick $tick should ${if (expected) "" else "not "}be a keyframe",
            )
        }
        assertTrue(ticks.none { it < sparseStart }, "the ring kept something older than its window")

        val dense = held.filter { it.kind == SnapshotKind.Dense }
        assertEquals(RingConfig.DEFAULT_DENSE_TICKS, dense.size)
        assertTrue(dense.all { newest.ticksSince(it.tick) < RingConfig.DEFAULT_DENSE_TICKS })
    }

    @Test
    fun `every slot the ring drops goes back to the pool rather than being thrown away`() {
        val ring = SnapshotRing(registry)
        fill(ring, TICKS)

        assertEquals(
            ring.slotCount,
            ring.size + ring.pooledCount,
            "a slot is either held or pooled; anything else is a slot that was allocated twice",
        )
        assertTrue(
            ring.slotCount < TICKS,
            "the ring allocated ${ring.slotCount} slots for $TICKS ticks; slots must be recycled",
        )
    }

    @Test
    fun `nearestAtOrBefore answers for aligned, unaligned, current and out-of-range ticks`() {
        val ring = SnapshotRing(registry)
        fill(ring, TICKS)
        val newest = Tick(TICKS.toLong())

        assertEquals(newest, ring.nearestAtOrBefore(newest)?.tick, "the exact current tick")
        assertEquals(newest, ring.nearestAtOrBefore(newest + 500L)?.tick, "beyond the newest")

        val insideDense = newest - 60L
        assertEquals(insideDense, ring.nearestAtOrBefore(insideDense)?.tick, "inside the dense window")

        val aligned = Tick(2_400L)
        assertEquals(aligned, ring.nearestAtOrBefore(aligned)?.tick, "a keyframe-aligned tick")

        val unaligned = Tick(2_401L)
        assertEquals(
            aligned,
            ring.nearestAtOrBefore(unaligned)?.tick,
            "an unaligned tick must fall back to the keyframe before it",
        )

        assertNull(ring.nearestAtOrBefore(Tick(-1)), "older than the ring")
    }

    @Test
    fun `degrade doubles the keyframe spacing and keeps the sixty-second window spanned`() {
        val ring = SnapshotRing(registry, log = Log.NoOp)
        fill(ring, TICKS)
        val spanBefore = ring.newestTick()!!.ticksSince(ring.oldestTick()!!)

        assertTrue(ring.degrade())

        assertEquals(RingConfig.DEFAULT_SPARSE_INTERVAL * 2, ring.sparseInterval)
        assertEquals(1, ring.degradeCount)
        val ticks = ring.listSnapshots().map { it.tick.value }
        val sparse = ticks.filter { TICKS - it >= RingConfig.DEFAULT_DENSE_TICKS }
        assertTrue(
            sparse.all { it % ring.sparseInterval == 0L },
            "slots that no longer land on a keyframe must have been released",
        )
        val spanAfter = ring.newestTick()!!.ticksSince(ring.oldestTick()!!)
        assertTrue(
            spanAfter >= spanBefore - ring.sparseInterval,
            "the rewind window shrank from $spanBefore to $spanAfter ticks; degrading may cost " +
                "at most one keyframe spacing of reach, and must never cost the window",
        )
        assertTrue(
            spanAfter >= RingConfig.DEFAULT_SPARSE_WINDOW_TICKS - ring.sparseInterval,
            "the sixty-second window must still be spanned, was $spanAfter ticks",
        )
    }

    @Test
    fun `a ring over its byte budget degrades rather than dropping the feature`() {
        // A budget small enough that a few hundred entity-ticks blow through it, which is the
        // slow-hardware case spec 7 says must degrade the cadence and never drop the window.
        val ring = SnapshotRing(registry, RingConfig(budgetBytes = 200_000L), Log.NoOp)
        fill(ring, TICKS)

        assertTrue(ring.degradeCount > 0, "the ring should have degraded under a 200KB budget")
        assertTrue(
            ring.sparseInterval > RingConfig.DEFAULT_SPARSE_INTERVAL,
            "sparseInterval stayed at ${ring.sparseInterval}",
        )
        assertTrue(ring.size > 0, "degrading must never empty the ring")
        assertTrue(
            ring.nearestAtOrBefore(Tick(TICKS - 1L)) != null,
            "the dense window must survive degradation, or rollback breaks",
        )
    }

    @Test
    fun `degrade refuses once the keyframe spacing has reached the dense window`() {
        val ring = SnapshotRing(registry, RingConfig(denseTicks = 12, sparseInterval = 6))
        fill(ring, 200)

        assertTrue(ring.degrade())
        assertEquals(12, ring.sparseInterval)
        assertTrue(!ring.degrade(), "there is nothing left to give up past the dense window")
        assertEquals(12, ring.sparseInterval)
    }

    @Test
    fun `an exhausted ring reports itself once and then costs nothing per commit`() {
        // The dense window alone is over budget, so `enforceBudget` can neither degrade (the
        // interval already reaches `denseTicks`) nor evict (the eviction loop breaks on a dense
        // slot). That is a permanent over-budget state reached on a real machine — the
        // 1000-entity ring runs at 95% of the 64MB ceiling — and it is on the commit path,
        // which SnapshotBudgets gates at zero bytes.
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")
        val log = CountingLog()
        val ring = SnapshotRing(
            registry,
            RingConfig(denseTicks = 8, sparseWindowTicks = 8, sparseInterval = 8, budgetBytes = 1L),
            log,
        )
        var tick = 0

        repeat(64) { commitAt(ring, ++tick) }

        assertTrue(ring.isExhausted, "a ring that can neither degrade nor evict is exhausted")
        assertTrue(ring.totalBytes > ring.budgetBytes, "the scenario must leave it over budget")
        assertEquals(
            1,
            log.warnings,
            "an exhausted ring logged ${log.warnings} times over 64 commits; it must latch and " +
                "say so once, or a permanently exhausted ring writes a line every tick forever",
        )

        val allocated = AllocationProbe.bytesAllocated { commitAt(ring, ++tick) }

        assertEquals(
            0L,
            allocated,
            "a commit into an exhausted ring allocated $allocated bytes; the refusal branch is " +
                "building its message on every tick",
        )
        assertEquals(1, log.warnings, "and it must not start logging again either")
    }

    @Test
    fun `holds answers whether a tick is held without allocating`() {
        // `SnapshotTimeTravel.captureNow` asks this on every capture to stay idempotent per
        // tick. Answering it with `infoOf(tick) != null` builds a SnapshotInfo per captured
        // tick, on the path spec 7 budgets at zero.
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")
        val ring = SnapshotRing(registry)
        fill(ring, TICKS)
        val oldest = ring.oldestTick()!!
        val newest = ring.newestTick()!!

        assertTrue(ring.holds(newest), "the newest tick is held")
        assertTrue(ring.holds(oldest), "the oldest tick is held")
        assertTrue(!ring.holds(newest + 1L), "a tick the ring never saw is not held")
        assertTrue(!ring.holds(oldest - 1L), "a tick the ring has evicted is not held")
        assertTrue(
            !ring.holds(Tick(TICKS - RingConfig.DEFAULT_DENSE_TICKS - 1L)),
            "an interior tick that fell out of the dense window without landing on a keyframe " +
                "is not held, and holds must not report the nearest one instead",
        )

        val allocated = AllocationProbe.bytesAllocated {
            ring.holds(newest)
            ring.holds(newest + 1L)
        }

        assertEquals(0L, allocated, "holds allocated $allocated bytes")
    }

    @Test
    fun `clear returns every held slot to the pool, as a scene swap must`() {
        val ring = SnapshotRing(registry)
        fill(ring, 400)
        val slots = ring.slotCount
        assertTrue(ring.size > 0)

        ring.clear()

        assertEquals(0, ring.size)
        assertEquals(slots, ring.pooledCount, "every slot must come back, none re-allocated")
        assertEquals(0L, ring.totalBytes)
        assertNull(ring.newestTick())
    }

    @Test
    fun `a released slot is handed straight back out rather than a new one being built`() {
        val ring = SnapshotRing(registry)
        val first = ring.acquire()
        ring.release(first)

        assertSame(first, ring.acquire(), "acquire must recycle")
        assertEquals(1, ring.slotCount)
    }

    @Test
    fun `dropAfter releases the future a restore has just unwound`() {
        val ring = SnapshotRing(registry)
        fill(ring, 300)
        val pooledBefore = ring.pooledCount

        val dropped = ring.dropAfter(Tick(250))

        assertEquals(50, dropped)
        assertEquals(Tick(250), ring.newestTick())
        assertEquals(pooledBefore + 50, ring.pooledCount)
        // And the ring will accept a re-captured tick 251, which it would not if the old one
        // were still held.
        commitAt(ring, 251)
        assertEquals(Tick(251), ring.newestTick())
    }

    @Test
    fun `a snapshot that is not newer than the ring's newest is refused`() {
        val ring = SnapshotRing(registry)
        fill(ring, 10)

        val failure = assertFailsWith<IllegalArgumentException> { commitAt(ring, 10) }
        assertTrue(failure.message!!.contains("not newer"), failure.message!!)
    }

    @Test
    fun `push and evict allocates nothing once the pool has reached its high water`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")
        val ring = SnapshotRing(registry)
        var tick = 0

        // 1800 ticks of push-and-evict: long enough that the ring is at its steady population,
        // the pool is at its high water and no ArrayList has any growing left to do.
        repeat(WARMUP_TICKS) { commitAt(ring, ++tick) }

        val allocated = AllocationProbe.bytesAllocated { commitAt(ring, ++tick) }

        assertEquals(0L, allocated, "a steady-state push allocated $allocated bytes")
    }

    @Test
    fun `an empty ring reports nothing rather than failing`() {
        val ring = SnapshotRing(registry)

        assertNull(ring.newestTick())
        assertNull(ring.oldestTick())
        assertNull(ring.nearestAtOrBefore(Tick(5)))
        assertEquals(emptyList(), ring.listSnapshots())
        assertNull(ring.infoOf(Tick(5)))
    }

    @Test
    fun `listSnapshots reports each slot's window and its size`() {
        val ring = SnapshotRing(registry)
        fill(ring, 400)

        val infos = ring.listSnapshots()
        assertEquals(infos.sortedBy { it.tick.value }, infos, "oldest first")
        assertTrue(infos.all { it.sizeBytes > 0 })
        assertEquals(ring.totalBytes, infos.sumOf { it.sizeBytes })
        assertNotNull(ring.infoOf(infos.last().tick))
    }

    /** Commits [ticks] consecutive snapshots, each with a small but real captured world. */
    private fun fill(ring: SnapshotRing, ticks: Int) {
        for (tick in 1..ticks) commitAt(ring, tick)
    }

    /**
     * Commits one slot for [tick].
     *
     * The slot is filled directly rather than by capturing a live world: this file is about
     * retention, eviction and pooling, and driving 3600 real ticks through a Fleks world for
     * each case would make every test here also a test of the simulation.
     */
    private fun commitAt(ring: SnapshotRing, tick: Int) {
        val slot = ring.acquire()
        slot.tick = Tick(tick.toLong())
        slot.isFilled = true
        val movement = registry.indexOf(MovementReplicator.typeId)
        for (entity in 0 until ENTITIES) {
            val row = slot.fields.appendRow(dev.wildware.udea.core.identity.NetId.of(entity, 0))
            val at = slot.fields.claimSlot(row, movement)
            slot.fields.storeAt(movement).setFloat(at, MovementReplicator.POSITION_X, tick.toFloat())
        }
        ring.commit(slot)
    }

    /** A [Log] that counts, so "warns once" is an assertion rather than an eyeball on stderr. */
    private class CountingLog : Log {
        var warnings: Int = 0
            private set

        override fun debug(message: String): Unit = Unit
        override fun info(message: String): Unit = Unit
        override fun error(message: String, cause: Throwable?): Unit = Unit

        override fun warn(message: String) {
            warnings++
        }
    }

    private companion object {
        const val TICKS: Int = 3_600
        const val WARMUP_TICKS: Int = 1_800
        const val ENTITIES: Int = 16
    }
}
