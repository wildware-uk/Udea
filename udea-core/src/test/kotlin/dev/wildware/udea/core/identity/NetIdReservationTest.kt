package dev.wildware.udea.core.identity

import com.github.quillraven.fleks.Entity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
