package dev.wildware.udea.net.replication

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.harness.Vitals
import dev.wildware.udea.net.harness.VitalsReplicator
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.wire.ReplicaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A component the server drops has to stop existing on the client, and a wave of destroys has to
 * survive not fitting in one datagram.
 *
 * Both were holes rather than bugs: the wire had no way to say "this component is gone", so a
 * `Combatant` dropped when a unit died stuck to a client's copy for the rest of the session and
 * accumulated monotonically; and the removal section was written with no overflow path at all, so
 * enough simultaneous destroys would have run the bit buffer past the datagram and lost them
 * silently.
 */
class ComponentRemovalTest {

    private fun dropVitals(world: NetTestWorld, netId: NetId) {
        val entity = world.netIds.resolveOrNull(netId) ?: error("$netId is not live")
        with(world.world) { entity.configure { it -= Vitals } }
    }

    private fun vitalsIndex(session: ReplicationSession): Int =
        session.world.registry.indexOf(VitalsReplicator.typeId)

    @Test
    fun `a component the server drops is removed from the client`() {
        lateinit var session: ReplicationSession
        var netId: NetId = NetId.NONE
        session = ReplicationSession(
            seed = 41L,
            mutate = { tick ->
                when (tick.value) {
                    1L -> netId = session.world.spawn(3f, 4f)
                    20L -> dropVitals(session.world, netId)
                    else -> Unit
                }
            },
        )

        session.step(15)
        val store = session.clients.single().world
        assertTrue(
            store.slotOf(store.rowOf(netId), vitalsIndex(session)) != ReplicaStore.ABSENT,
            "the client never received Vitals in the first place, so its removal proves nothing",
        )

        session.step(30)
        assertTrue(netId in store, "the whole entity was destroyed instead of one component")
        assertEquals(
            ReplicaStore.ABSENT,
            store.slotOf(store.rowOf(netId), vitalsIndex(session)),
            "the client kept a component the server dropped",
        )
        val report = DesyncReport.compare(
            session.world.registry,
            session.serverStateAt(session.clients.single().serverTick).fields,
            store,
        )
        assertTrue(report.isEmpty(), "a component removal desynced the world:\n" + report.joinToString("\n"))
    }

    @Test
    fun `a component removal survives the loss of the datagram carrying it`() {
        lateinit var session: ReplicationSession
        var netId: NetId = NetId.NONE
        session = ReplicationSession(
            seed = 42L,
            conditions = NetConditions(latencyTicks = 3, lossChance = 0.4f),
            mutate = { tick ->
                when (tick.value) {
                    1L -> netId = session.world.spawn(3f, 4f)
                    20L -> dropVitals(session.world, netId)
                    else -> Unit
                }
            },
        )
        session.step(90)

        val store = session.clients.single().world
        assertEquals(
            ReplicaStore.ABSENT,
            store.slotOf(store.rowOf(netId), vitalsIndex(session)),
            "a lost removal was never retried",
        )
    }

    @Test
    fun `destroys that exceed the bandwidth budget are deferred, not lost`() {
        val session = destroyWave(budgetBytes = 48, entities = 80, seed = 43L)
        assertTrue(
            session.server.removalDeferrals > 0,
            "80 destroys into a 48 byte budget never deferred a removal",
        )
        assertEquals(
            0,
            session.clients.single().world.entityCount,
            "destroys were lost when the removal section ran out of budget",
        )
    }

    @Test
    fun `destroys that overflow the datagram itself are deferred, not truncated`() {
        // The budget is set well above the MTU on purpose. At the default the budget check fires
        // first and the `BitBufferOverflow` path is never reached, so the test would assert the
        // cheap guard and leave the expensive one - the one that decides whether a section is
        // truncated mid-record - completely unexercised.
        val session = destroyWave(budgetBytes = 100_000, entities = 700, seed = 44L, settle = 900)
        assertTrue(
            session.server.removalDeferrals > 0,
            "700 destroys never overflowed a 1200 byte datagram",
        )
        assertEquals(
            0,
            session.clients.single().world.entityCount,
            "the removal section was truncated and destroys were lost",
        )
    }

    private fun destroyWave(
        budgetBytes: Int,
        entities: Int,
        seed: Long,
        settle: Int = 399,
    ): ReplicationSession {
        lateinit var session: ReplicationSession
        val ids = ArrayList<NetId>()
        val wave = settle + 1L
        session = ReplicationSession(
            seed = seed,
            budgetBytes = budgetBytes,
            mutate = { tick ->
                if (tick.value == 1L) repeat(entities) { ids += session.world.spawn(it.toFloat(), 0f) }
                if (tick.value == wave) for (netId in ids) session.world.despawn(netId)
            },
        )
        session.step(settle)
        assertEquals(
            entities,
            session.clients.single().world.entityCount,
            "the client never learned all $entities entities before the wave",
        )
        session.step(120)
        return session
    }
}
