package dev.wildware.udea.core.identity

import com.github.quillraven.fleks.Entity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NetIdIndex.reserve] and [NetIdIndex.attach]: an id that exists before its entity does.
 *
 * `BlueprintSpawner` has to answer "what did I just spawn?" at submit time and create the entity
 * at the next barrier drain, so between those two moments an index is taken and empty. Every
 * property that window needs is asserted here, because each one of them fails silently: an index
 * handed out twice aliases two entities, a reserved index visited by `forEachLive` puts a row
 * with no component data into a snapshot, and an `attach` that tolerated a missing reservation
 * would bind an id nobody is holding.
 */
class NetIdReservationTest {

    @Test
    fun `a reserved id is live but resolves to nothing until it is attached`() {
        val index = NetIdIndex(capacity = 8, entityCapacity = 8)

        val id = index.reserve()

        assertEquals(1, index.liveCount)
        assertEquals(1, index.reservedCount)
        assertNull(index.resolveOrNull(id), "no entity exists yet, so the id must not resolve")
        assertTrue(id !in index)
    }

    @Test
    fun `attaching completes the reservation in both directions`() {
        val index = NetIdIndex(capacity = 8, entityCapacity = 8)
        val entity = Entity(3, version = 0u)

        val id = index.reserve()
        index.attach(entity, id)

        assertEquals(entity, index.resolveOrNull(id))
        assertEquals(id, index.netIdOf(entity))
        assertEquals(0, index.reservedCount)
        assertEquals(1, index.liveCount)
    }

    @Test
    fun `a reservation is never handed out again while it is outstanding`() {
        val index = NetIdIndex(capacity = 8, entityCapacity = 8)

        val reserved = index.reserve()
        val allocated = index.allocate(Entity(0, version = 0u))
        val alsoReserved = index.reserve()

        assertEquals(
            listOf(reserved, allocated, alsoReserved).distinct().size,
            3,
            "reserve and allocate draw from one index space and must never collide",
        )
    }

    @Test
    fun `forEachLive skips a reservation, so a snapshot taken mid-spawn holds no empty row`() {
        val index = NetIdIndex(capacity = 8, entityCapacity = 8)
        val settled = index.allocate(Entity(1, version = 0u))
        index.reserve()

        val visited = ArrayList<NetId>()
        index.forEachLive { netId, _ -> visited += netId }

        assertEquals(listOf(settled), visited)
    }

    @Test
    fun `attaching to something that was never reserved fails loudly`() {
        val index = NetIdIndex(capacity = 8, entityCapacity = 8)
        val live = index.allocate(Entity(1, version = 0u))

        assertFailsWith<IllegalStateException> { index.attach(Entity(2, version = 0u), live) }
        assertFailsWith<IllegalStateException> { index.attach(Entity(2, version = 0u), NetId.of(5, 0)) }
        assertFailsWith<IllegalArgumentException> { index.attach(Entity(2, version = 0u), NetId.NONE) }
    }

    @Test
    fun `attaching an entity that already holds an id fails loudly`() {
        val index = NetIdIndex(capacity = 8, entityCapacity = 8)
        val entity = Entity(1, version = 0u)
        index.allocate(entity)
        val reserved = index.reserve()

        assertFailsWith<IllegalArgumentException> { index.attach(entity, reserved) }
    }

    @Test
    fun `freeing an unattached reservation gives the index back and clears the counter`() {
        val index = NetIdIndex(capacity = 8, entityCapacity = 8)

        val abandoned = index.reserve()
        assertTrue(index.free(abandoned))

        assertEquals(0, index.reservedCount)
        assertEquals(0, index.liveCount)
        assertNull(index.resolveOrNull(abandoned), "the freed id must read stale")
    }

    @Test
    fun `a snapshot taken while a spawn is queued does not leak the reserved index`() {
        // The ordering the engine actually produces: `BlueprintSpawner.spawn` reserves, and the
        // capture at the end of the tick happens before the barrier drain that would attach.
        // A reserved index is in none of the three places a restore rebuilds from — it is not
        // in the free ring, `forEachLive` skips it so the roster has no row, and it is below
        // `nextFresh` — so unless `saveInto` records it, `takeIndex` can never hand it out
        // again and every rewind across a queued spawn leaks one index for good.
        val index = NetIdIndex(capacity = 4, entityCapacity = 8)
        val settled = index.allocate(Entity(1, version = 0u))
        val queued = index.reserve()

        val state = HandleState()
        index.saveInto(state)

        // The restore as `SnapshotService` performs it: reset the allocator, re-bind the roster.
        index.restoreFrom(state)
        index.bind(Entity(1, version = 0u), settled)

        assertEquals(1, index.liveCount, "only the rostered entity is live after the restore")
        assertNull(index.resolveOrNull(queued), "the unwound reservation must read stale")
        assertFalse(
            index.isOutstandingReservation(queued),
            "a restore unwinds the future the reservation named, so attach must still refuse it",
        )
        assertFailsWith<IllegalStateException> { index.attach(Entity(7, version = 0u), queued) }

        // Three ids remain in a four-id index: the reservation's recycled index and two fresh.
        val handed = List(3) { index.allocate(Entity(it + 2, version = 0u)) }
        assertEquals(
            listOf(queued.index, 2, 3),
            handed.map { it.index },
            "the reserved index must come back, at the head of the queue as FIFO puts it",
        )
        assertEquals(
            queued.generation + 1,
            handed.first().generation,
            "and re-minted a generation on, or the id the spawn is still holding would alias " +
                "the new occupant",
        )
        assertFailsWith<NetIdExhaustedException> { index.allocate(Entity(9, version = 0u)) }
    }

    @Test
    fun `restoring a snapshot drops outstanding reservations`() {
        val index = NetIdIndex(capacity = 8, entityCapacity = 8)
        index.allocate(Entity(1, version = 0u))
        val state = HandleState()
        index.saveInto(state)

        index.reserve()
        index.restoreFrom(state)

        assertEquals(
            0,
            index.reservedCount,
            "a restore replaces the whole population; a reservation names an entity in a future " +
                "that has just been unwound",
        )
        assertEquals(0, index.liveCount)
    }
}
