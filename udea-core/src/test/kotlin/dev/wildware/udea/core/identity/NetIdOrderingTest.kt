package dev.wildware.udea.core.identity

import com.github.quillraven.fleks.Entity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Capture order must be a function of the live set, not of how it was reached.
 *
 * Snapshot capture and full writes walk the live ids in order. If that order depended on
 * allocation history, two processes holding identical state would produce different bytes,
 * and `desync_report` would report a desync that was really just a different insertion
 * order.
 */
class NetIdOrderingTest {

    @Test
    fun `two indices built by different insertion orders capture in the same order`() {
        val entities = List(4) { Entity(it, 0u) }

        // Built straight through, then index 1 recycled onto the same entity.
        val direct = NetIdIndex(capacity = 32)
        val directIds = entities.map { direct.allocate(it) }
        direct.free(directIds[1])
        direct.allocate(entities[1])

        // Same final live set, reached with an unrelated entity occupying index 1 first.
        val roundabout = NetIdIndex(capacity = 32)
        roundabout.allocate(entities[0])
        val interloperId = roundabout.allocate(Entity(99, 0u))
        roundabout.allocate(entities[2])
        roundabout.allocate(entities[3])
        roundabout.free(interloperId)
        roundabout.allocate(entities[1])

        assertEquals(captureOrder(direct), captureOrder(roundabout))
        assertEquals(
            listOf(
                NetId.of(0, 0) to entities[0],
                NetId.of(1, 1) to entities[1],
                NetId.of(2, 0) to entities[2],
                NetId.of(3, 0) to entities[3],
            ),
            captureOrder(direct),
        )
    }

    @Test
    fun `capture order is ascending NetId order`() {
        val index = NetIdIndex(capacity = 32)
        val ids = List(6) { index.allocate(Entity(it, 0u)) }
        // Churn the middle so the free list is not in index order.
        index.free(ids[4])
        index.free(ids[1])
        index.allocate(Entity(20, 0u))
        index.allocate(Entity(21, 0u))

        val captured = captureOrder(index).map { it.first }

        assertEquals(captured.sorted(), captured)
        assertEquals(6, captured.size)
    }

    @Test
    fun `freeing changes the live set but not the ordering rule`() {
        val index = NetIdIndex(capacity = 32)
        val ids = List(5) { index.allocate(Entity(it, 0u)) }
        index.free(ids[2])

        val captured = captureOrder(index).map { it.first.index }

        assertEquals(listOf(0, 1, 3, 4), captured)
        assertEquals(4, index.liveCount)
    }

    @Test
    fun `the ordering test would notice an insertion-order-dependent walk`() {
        // Control: two indices with different live sets must not compare equal, or the
        // assertions above would pass for a broken implementation.
        val first = NetIdIndex(capacity = 32)
        first.allocate(Entity(0, 0u))
        val second = NetIdIndex(capacity = 32)
        second.allocate(Entity(1, 0u))

        assertNotEquals(captureOrder(first), captureOrder(second))
    }

    private fun captureOrder(index: NetIdIndex): List<Pair<NetId, Entity>> {
        val visited = ArrayList<Pair<NetId, Entity>>()
        index.forEachLive { netId, entity -> visited += netId to entity }
        return visited
    }
}
