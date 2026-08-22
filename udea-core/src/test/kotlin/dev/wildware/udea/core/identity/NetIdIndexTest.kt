package dev.wildware.udea.core.identity

import com.github.quillraven.fleks.Entity
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class NetIdIndexTest {

    @Test
    fun `an allocated id resolves to the entity it was allocated for`() {
        val index = NetIdIndex(capacity = 64)
        val entity = Entity(3, 0u)

        val id = index.allocate(entity)

        assertSame(entity, index.resolveOrNull(id))
        assertEquals(id, index.netIdOf(entity))
        assertTrue(id in index)
        assertEquals(1, index.liveCount)
    }

    @Test
    fun `NONE and never-allocated ids resolve to null`() {
        val index = NetIdIndex(capacity = 64)
        index.allocate(Entity(0, 0u))

        assertNull(index.resolveOrNull(NetId.NONE))
        assertNull(index.resolveOrNull(NetId.of(1, 0)))
        assertNull(index.resolveOrNull(NetId.of(63, 7)))
        assertEquals(NetId.NONE, index.netIdOf(Entity(41, 0u)))
    }

    @Test
    fun staleReferenceIsDetected() {
        // The whole reason the id carries a generation. Without it the old NetId would
        // silently resolve to whoever moved into the recycled slot, and the bug would
        // surface as gameplay happening to the wrong entity.
        val index = NetIdIndex(capacity = 64)
        val original = Entity(1, 0u)
        val replacement = Entity(2, 0u)

        val staleId = index.allocate(original)
        assertTrue(index.free(staleId))

        val freshId = index.allocate(replacement)

        assertEquals(
            staleId.index,
            freshId.index,
            "the free list must hand the index straight back, or this test proves nothing",
        )
        assertNotEquals(staleId, freshId)
        assertEquals(staleId.generation + 1, freshId.generation)

        assertNull(index.resolveOrNull(staleId), "a stale id must be detectable, not aliased")
        assertSame(replacement, index.resolveOrNull(freshId))
        assertEquals(NetId.NONE, index.netIdOf(original))
    }

    @Test
    fun `a recycled Fleks entity id does not inherit the previous NetId`() {
        // Fleks recycles its own slot indices, so the reverse lookup is keyed by something
        // that is itself reused. The candidate has to be verified, not trusted.
        val index = NetIdIndex(capacity = 64)
        val first = Entity(5, 0u)
        val recycled = Entity(5, 1u)

        val firstId = index.allocate(first)
        assertTrue(index.free(firstId))

        assertEquals(NetId.NONE, index.netIdOf(recycled))

        val recycledId = index.allocate(recycled)
        assertEquals(recycledId, index.netIdOf(recycled))
        assertEquals(NetId.NONE, index.netIdOf(first))
    }

    @Test
    fun `freeing is idempotent and never throws`() {
        val index = NetIdIndex(capacity = 64)
        val id = index.allocate(Entity(0, 0u))

        assertTrue(index.free(id))
        assertFalse(index.free(id), "a double free is reported, not fatal")
        assertFalse(index.free(NetId.NONE))
        assertFalse(index.free(NetId.of(63, 0)))
        assertEquals(0, index.liveCount)
    }

    @Test
    fun `allocating the same entity twice is rejected`() {
        val index = NetIdIndex(capacity = 64)
        val entity = Entity(4, 0u)
        index.allocate(entity)

        assertFailsWith<IllegalArgumentException> { index.allocate(entity) }
    }

    @Test
    fun `the free list is FIFO, so a freed index goes to the back of the queue`() {
        val index = NetIdIndex(capacity = 64)
        val ids = List(4) { index.allocate(Entity(it, 0u)) }

        assertEquals(listOf(0, 1, 2, 3), ids.map { it.index })

        index.free(ids[1])
        index.free(ids[2])

        assertEquals(1, index.allocate(Entity(10, 0u)).index, "oldest free index first")
        assertEquals(2, index.allocate(Entity(11, 0u)).index)
        assertEquals(4, index.allocate(Entity(12, 0u)).index, "fresh indices only once the queue is empty")
    }

    @Test
    fun `the generation wraps after 256 recycles, which is the documented limit`() {
        val index = NetIdIndex(capacity = 4)
        val firstId = index.allocate(Entity(0, 0u))
        assertEquals(0, firstId.generation)
        assertTrue(index.free(firstId))

        var id = firstId
        repeat(NetId.GENERATION_MODULUS - 1) {
            id = index.allocate(Entity(1, 0u))
            assertNull(
                index.resolveOrNull(firstId),
                "the original id must stay stale for the whole generation cycle",
            )
            assertTrue(index.free(id))
        }

        val wrapped = index.allocate(Entity(2, 0u))
        assertEquals(firstId, wrapped, "generation is 8 bits, so it wraps after exactly 256 recycles")
        assertEquals(
            Entity(2, 0u),
            index.resolveOrNull(firstId),
            "past the wrap a stale id can alias again; the FIFO free list is what makes reaching it unlikely",
        )
    }

    @Test
    fun `exhaustion is a typed error, not an array crash`() {
        val index = NetIdIndex(capacity = 8)
        repeat(8) { index.allocate(Entity(it, 0u)) }

        val failure = assertFailsWith<NetIdExhaustedException> { index.allocate(Entity(99, 0u)) }
        assertEquals(8, failure.capacity)
        assertTrue("8" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `capacity outside the addressable range is rejected`() {
        assertFailsWith<IllegalArgumentException> { NetIdIndex(capacity = 0) }
        assertFailsWith<IllegalArgumentException> { NetIdIndex(capacity = NetId.MAX_INDICES + 1) }
    }

    @Test
    fun `clear frees everything and leaves prior ids stale`() {
        val index = NetIdIndex(capacity = 16)
        val ids = List(5) { index.allocate(Entity(it, 0u)) }

        index.clear()

        assertEquals(0, index.liveCount)
        ids.forEach { assertNull(index.resolveOrNull(it)) }
    }

    @Test
    fun `resolveOrNull is a constant-time array read`() {
        // Replaces common/utils.kt:35-36, which resolved an inbound network entity with a
        // linear `find` over the Networkable family, once per packet. A linear scan at
        // 64 000 live ids is roughly a thousand times the cost it is at 64, so it cannot
        // pass a 2x bound however the timing noise falls.
        val smallNanos = timeResolutions(liveIds = 64)
        val largeNanos = timeResolutions(liveIds = 64_000)

        assertTrue(
            largeNanos <= smallNanos * 2,
            "resolution at 64 000 live ids took ${largeNanos}ns vs ${smallNanos}ns at 64: " +
                "that is ${largeNanos.toDouble() / smallNanos}x, so resolution is not O(1)",
        )
    }

    @Test
    fun allocateAndFreeAreAllocationFree() {
        val threadBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(
            threadBean != null && threadBean.isThreadAllocatedMemorySupported,
            "per-thread allocation accounting is unavailable on this JVM",
        )
        requireNotNull(threadBean).isThreadAllocatedMemoryEnabled = true

        val index = NetIdIndex(capacity = 1024)
        val entities = Array(CYCLE_ENTITIES) { Entity(it, 0u) }

        cycle(index, entities, CYCLES) // warm up: let the JIT compile and the arrays settle
        cycle(index, entities, CYCLES)

        val threadId = Thread.currentThread().id
        val before = threadBean.getThreadAllocatedBytes(threadId)
        cycle(index, entities, CYCLES)
        val allocated = threadBean.getThreadAllocatedBytes(threadId) - before

        assertEquals(
            0L,
            allocated,
            "$CYCLES allocate/free cycles allocated $allocated bytes; the snapshot ring's " +
                "per-tick budget depends on this path allocating nothing",
        )
        assertEquals(0, index.liveCount)
    }

    /** Runs [cycles] allocate-then-free rounds over [entities]. Must allocate nothing. */
    private fun cycle(index: NetIdIndex, entities: Array<Entity>, cycles: Int) {
        var done = 0
        while (done < cycles) {
            for (slot in entities.indices) {
                val id = index.allocate(entities[slot])
                index.free(id)
            }
            done += entities.size
        }
    }

    /** Best-of-[TIMING_TRIALS] nanoseconds to resolve [SAMPLES] ids spread across the live set. */
    private fun timeResolutions(liveIds: Int): Long {
        val index = NetIdIndex(capacity = NetId.MAX_INDICES, entityCapacity = liveIds + 1)
        val ids = IntArray(liveIds)
        for (i in 0 until liveIds) ids[i] = index.allocate(Entity(i, 0u)).raw

        val sampled = IntArray(SAMPLES) { ids[it * (liveIds / SAMPLES)] }

        var best = Long.MAX_VALUE
        repeat(TIMING_TRIALS) {
            var sink = 0
            val start = System.nanoTime()
            repeat(TIMING_REPEATS) {
                for (raw in sampled) {
                    if (index.resolveOrNull(NetId.ofRaw(raw)) != null) sink++
                }
            }
            val elapsed = System.nanoTime() - start
            check(sink == SAMPLES * TIMING_REPEATS)
            if (elapsed < best) best = elapsed
        }
        return best
    }

    private companion object {
        const val CYCLES: Int = 100_000
        const val CYCLE_ENTITIES: Int = 256
        const val SAMPLES: Int = 64
        const val TIMING_REPEATS: Int = 2_000
        const val TIMING_TRIALS: Int = 9
    }
}
