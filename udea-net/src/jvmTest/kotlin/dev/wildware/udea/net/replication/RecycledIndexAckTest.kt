package dev.wildware.udea.net.replication

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.EntityOp
import dev.wildware.udea.net.wire.SnapshotApplySink
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **An acknowledgement for a removal says the client dropped an entity, never that it holds one.**
 *
 * A `Destroy` is written every tick until it is acknowledged, so two or three packets carry the
 * same removal and their acks arrive over the following ticks. By the time the later ones land the
 * slot has usually been retired and handed to the next occupant, and `acknowledge` treated *any*
 * record naming a generation other than the tracked one as "a genuinely new entity, whatever the
 * old one's fate was" - without asking what the record actually said. So the late ack for a
 * `Destroy` re-tracked the **dead** generation and stamped it with a baseline: the tick of a
 * packet whose only mention of the entity was the record saying it was gone.
 *
 * From there the index never settles. The resurrected generation has no row, so the next tick
 * writes its `Destroy` again; its ack re-tracks the *other* dead generation; and the two take
 * turns for the rest of the session, one removal per tick, for an entity that died once. Nothing
 * reports it - the datagrams are well formed and the client discards each removal as a duplicate.
 *
 * The fatal end of it needs the 8-bit generation counter to come round: when the index is handed
 * back out at the generation being falsely tracked, the packer sees a live row *and* a baseline
 * and writes a **delta** against a state the client has never held. `moba`'s `runServer` died on
 * exactly that at tick 52559, `MalformedBitStream: snapshot carries an Update for NetId(#40@0),
 * which this client does not hold`, after fifteen minutes of a game that was fine until then.
 */
class RecycledIndexAckTest {

    @Test
    fun `an index whose occupants die settles instead of removing them for ever`() {
        lateinit var session: ReplicationSession
        var live: NetId = NetId.NONE
        var born = 0L
        var churning = true
        session = ReplicationSession(
            mutate = { tick ->
                // Short lives, back to back: an occupant dies while its predecessor's `Destroy`
                // records are still being acknowledged, which is what a creep wave does to one
                // index and what puts two dead generations on it at once.
                if (!churning) return@ReplicationSession
                if (live == NetId.NONE) {
                    live = session.world.spawn(1f, 2f)
                    born = tick.value
                } else if (tick.value - born >= LIFETIME_TICKS) {
                    session.world.despawn(live)
                    live = NetId.NONE
                }
            },
        )

        var removalsApplied = 0
        session.clients.single().applySink = SnapshotApplySink { _, op, _, _ ->
            if (op == EntityOp.Destroy || op == EntityOp.Leave) removalsApplied++
        }

        session.step(CHURN_TICKS)

        // Stop the churn and let everything in flight land. A settled session has nothing left to
        // say about the index: every occupant is dead, every removal is acknowledged.
        churning = false
        if (live != NetId.NONE) session.world.despawn(live)
        session.step(SETTLE_TICKS)

        val before = removalsApplied
        session.step(QUIET_TICKS)

        assertEquals(
            before,
            removalsApplied,
            "the server is still sending removals for entities that died long ago: the slot never " +
                "retires, because each ack for one dead generation's `Destroy` re-tracks the other",
        )

        val state = session.server.stateOf(PeerId.client(1))
        for (index in 0 until state.trackedIndices) {
            assertEquals(
                false,
                state.isDestroyPending(index),
                "index $index is still awaiting confirmation of a removal the client acknowledged " +
                    "$QUIET_TICKS ticks ago",
            )
        }
    }

    private companion object {
        /** How long an occupant lives. Short enough to die inside its predecessor's ack window. */
        const val LIFETIME_TICKS: Long = 2L

        /** Long enough for one index to be recycled many times over. */
        const val CHURN_TICKS: Int = 200

        /** Ticks for the last removals to cross the link and be acknowledged. */
        const val SETTLE_TICKS: Int = 60

        /** The window over which a settled session must say nothing further. */
        const val QUIET_TICKS: Int = 60
    }
}
