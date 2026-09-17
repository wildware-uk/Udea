package dev.wildware.udea.net.replication

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.harness.MoverReplicator
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **A late acknowledgement for a dead occupant must not freeze the index's new one** (issue #219).
 *
 * An entity that dies before the acknowledgement of its create has come back is not tracked for
 * the client, so the server writes nothing about it when it dies - and the next occupant of the
 * index is created at once. Then the late acknowledgement arrives. `acknowledge` rightly records
 * that the client held the dead generation, and the next tick writes its `Destroy`.
 *
 * What went wrong was the rule that came with that `Destroy`: an index waiting for a removal to be
 * confirmed did not carry its new occupant. The client already held the new occupant - its create
 * had left before the late acknowledgement arrived - so the client kept it, frozen where it was
 * created, for a whole round trip. In `moba` that is a creep standing at its spawn point while the
 * server walks it down the lane, and `runUdpProof` reported it as `Position DIFFER`.
 *
 * The client never needed the `Destroy`. A `Create` for another generation replaces the row at
 * that index outright (`ReplicaStore.createRow`), and a removal names its generation exactly, so
 * the new occupant's create is itself the removal of the old one.
 */
class RecycledIndexStallTest {

    @Test
    fun `a new occupant keeps moving while its predecessor's late acknowledgement is settled`() {
        lateinit var session: ReplicationSession
        var dead: NetId = NetId.NONE
        var occupant: NetId = NetId.NONE
        session = ReplicationSession(
            conditions = NetConditions(latencyTicks = LATENCY_TICKS),
            mutate = { tick ->
                when (tick.value) {
                    // Lives shorter than a round trip, so its create is never acknowledged in time.
                    SPAWN_TICK -> dead = session.world.spawn(0f, 0f)
                    DEATH_TICK -> {
                        session.world.despawn(dead)
                        occupant = session.world.spawn(0f, 0f)
                    }
                }
                if (occupant != NetId.NONE) session.world.mover(occupant).x = tick.value.toFloat()
            },
        )
        val client = session.clients.single()
        val state = session.server.stateOf(PeerId.client(1))
        val mover = session.world.registry.indexOf(MoverReplicator.typeId)

        var retracked = false
        var compared = 0
        repeat(RUN_TICKS) {
            session.step(1)
            if (occupant == NetId.NONE) return@repeat
            assertEquals(dead.index, occupant.index, "the harness did not recycle the index")
            if (state.trackedGeneration(occupant.index) == dead.generation) retracked = true

            val row = client.world.rowOf(occupant)
            if (row == -1) return@repeat
            val server = session.serverStateAt(client.serverTick).fields
            val serverRow = server.rowOf(occupant)
            if (serverRow == -1) return@repeat
            val clientX = client.world.storeAt(mover).getFloat(client.world.slotOf(row, mover), MoverReplicator.X)
            val serverX = server.storeAt(mover).getFloat(server.componentSlotAt(serverRow, mover), MoverReplicator.X)
            compared++
            if (clientX != serverX) {
                fail(
                    "tick ${session.harness.clock.tick.value}: the client holds $occupant at x=$clientX " +
                        "where the server had x=$serverX at tick ${client.serverTick.value} - the server " +
                        "stopped updating it while it settled the removal of $dead",
                )
            }
        }
        assertTrue(
            retracked,
            "the late acknowledgement for $dead never arrived after $occupant was created, so this run " +
                "did not exercise the case",
        )
        assertTrue(compared > MIN_COMPARED, "the client held $occupant for only $compared ticks")
    }

    private companion object {
        /** Six ticks each way: a round trip of twelve, longer than the first occupant lives. */
        const val LATENCY_TICKS = 6

        const val SPAWN_TICK = 10L

        /** Three ticks of life: well inside the first acknowledgement's round trip. */
        const val DEATH_TICK = 13L

        const val RUN_TICKS = 90

        /** Ticks the client must hold the new occupant for, so the comparison ran at all. */
        const val MIN_COMPARED = 40
    }
}
