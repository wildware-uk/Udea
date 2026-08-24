package dev.wildware.udea.net.relevancy

import dev.wildware.udea.net.wire.ReplicaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The other half of fog: a unit that walks *out* of vision stops being held by the client, and
 * one that walks back in is given back **in full and current**.
 *
 * ## Why this needed its own test
 *
 * [FogOfWar] refusing an entity is only half a feature. `ReplicationServer` writes what is
 * relevant; before this, nothing wrote the fact that something had *stopped* being relevant, so
 * a unit that walked into fog stayed frozen on the client's screen at the last position it was
 * seen at — for ever, and visible to a maphack that read the client's own store. The engine had
 * an `EntityOp.Leave` on the wire and no code path that emitted one.
 *
 * ## The three properties, and why re-entry is the hard one
 *
 * Leaving is easy. Coming back is where a naive implementation breaks: the server's per-entity
 * baseline still names a tick the client no longer holds any row for, so the next packet is an
 * `Update` against a row that is gone and `SnapshotReader` throws
 * [dev.wildware.udea.net.wire.MalformedBitStream] — the client is disconnected by its own fog.
 * `ReplicationServer` therefore starts the slot over when it gives an entity back, and the third
 * assertion here is what says so: the returned position is the *current* one, not the one from
 * before the unit vanished, which is what a stale baseline would have produced.
 */
class FogLeaveEmissionTest {

    @Test
    fun `a unit that walks out of vision is taken off the client, and the wire says why`() {
        val session = FogWireSession()
        session.enemyVisible = true
        session.run(TICKS)
        val row = session.client.world.rowOf(session.enemy)
        assertNotEquals(ReplicaStore.ABSENT, row, "the setup must first put the enemy in view")
        assertEquals(0L, session.server.leaveWrites, "nothing has left yet")

        session.enemyVisible = false
        session.run(TICKS)

        assertEquals(
            ReplicaStore.ABSENT,
            session.client.world.rowOf(session.enemy),
            "the client still holds a unit it may no longer see: a maphack reads it straight out " +
                "of the store, which is the whole failure fog exists to prevent",
        )
        assertTrue(session.server.leaveWrites > 0L, "it must have gone as a Leave")
        assertEquals(
            0L,
            session.server.reentries,
            "nothing came back, so nothing should have been revived",
        )
    }

    @Test
    fun `a unit that walks back into vision is given back at its current position`() {
        val session = FogWireSession()
        session.enemyVisible = true
        session.run(TICKS)
        session.enemyVisible = false
        session.run(TICKS)
        assertEquals(ReplicaStore.ABSENT, session.client.world.rowOf(session.enemy))

        session.enemyVisible = true
        session.run(TICKS)

        val row = session.client.world.rowOf(session.enemy)
        assertNotEquals(
            ReplicaStore.ABSENT,
            row,
            "a unit that came back into vision was never given back: the client is permanently " +
                "blind to it, which is worse than having no fog at all",
        )
        assertTrue(session.server.reentries > 0L, "the slot must have been started over")
        assertEquals(
            session.enemyCoordinates.last(),
            session.clientX(session.enemy),
            "the client was given the position the unit had before it vanished, which means the " +
                "server delta-encoded against a baseline the client no longer holds",
        )
    }

    @Test
    fun `losing the Leave datagram does not strand the unit on the client`() {
        val session = FogWireSession()
        session.enemyVisible = true
        session.run(TICKS)
        session.enemyVisible = false

        // Drop everything for a while: the Leave is written and never arrives.
        session.dropServerToClient = true
        session.run(DROPPED_TICKS)
        session.dropServerToClient = false
        session.run(TICKS)

        assertEquals(
            ReplicaStore.ABSENT,
            session.client.world.rowOf(session.enemy),
            "a Leave that was lost was never written again, so the client keeps the unit for ever",
        )
    }

    private companion object {
        const val TICKS: Int = 40
        const val DROPPED_TICKS: Int = 10
    }
}
