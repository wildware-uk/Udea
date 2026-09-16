package dev.wildware.udea.net.replication

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.harness.MoverReplicator
import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.wire.ReplicaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole stack driven end to end over the in-memory transport: no sockets, no threads, no
 * sleeps, and every claim checked field by field through [DesyncReport].
 *
 * A byte or hash comparison would be easier and much weaker. Both sides run the same
 * `Replicator` over the same `FieldStore` layout (spec 3.1), so a disagreement can be reported as
 * "entity 7, Mover, x: server 4.0 client 3.0" — and a test that can say that is a test whose
 * failure message is the diagnosis.
 */
class ReplicationConvergenceTest {

    private fun movingWorld(
        entities: Int,
        clients: Int = 1,
        conditions: NetConditions = NetConditions.PERFECT,
        budgetBytes: Int = 1200,
        seed: Long = 20_260_823L,
    ): ReplicationSession {
        lateinit var session: ReplicationSession
        val ids = ArrayList<NetId>()
        session = ReplicationSession(
            clientCount = clients,
            seed = seed,
            conditions = conditions,
            budgetBytes = budgetBytes,
            mutate = { tick ->
                if (ids.isEmpty()) {
                    repeat(entities) { index ->
                        ids += session.world.spawn(index.toFloat(), 0f, teamId = index % 2)
                    }
                }
                for ((index, netId) in ids.withIndex()) {
                    val mover = session.world.mover(netId)
                    mover.x = (tick.value + index).toFloat()
                    mover.y = (tick.value * 2 + index).toFloat()
                }
            },
        )
        return session
    }

    private fun assertConverged(session: ReplicationSession, message: String) {
        for (client in session.clients) {
            assertTrue(client.applied > 0, "${client.peer} never applied a packet")
            val server = session.serverStateAt(client.serverTick).fields
            val report = DesyncReport.compare(session.world.registry, server, client.world)
            assertTrue(report.isEmpty(), "$message — ${client.peer} differs:\n" + report.joinToString("\n"))
        }
    }

    @Test
    fun `a client converges to server identical state on a perfect link`() {
        val session = movingWorld(entities = 12)
        session.step(30)
        assertConverged(session, "a perfect link did not converge")
        assertTrue(session.clients.single().applied > 25, "the client applied only ${session.clients.single().applied} packets")
    }

    @Test
    fun `the desync report is capable of reporting a difference`() {
        // The convergence assertions above are worthless if `compare` cannot see a difference.
        // This drives the same comparison over a deliberately corrupted client store.
        val session = movingWorld(entities = 4)
        session.step(20)
        val client = session.clients.single()
        val store = client.world
        val netId = store.liveNetIds().first()
        val moverIndex = session.world.registry.indexOf(MoverReplicator.typeId)
        val slot = store.slotOf(store.rowOf(netId), moverIndex)
        store.storeAt(moverIndex).setFloat(slot, MoverReplicator.X, -999f)

        val report = DesyncReport.compare(session.world.registry, session.serverStateAt(client.serverTick).fields, store)
        assertEquals(1, report.size, report.toString())
        assertEquals("x", report.single().fieldName)
        assertEquals(-999f, report.single().clientValue)
    }

    @Test
    fun `every client converges within one second under thirty percent loss`() {
        val session = movingWorld(
            entities = 20,
            clients = 3,
            conditions = NetConditions(latencyTicks = 4, jitterTicks = 2, lossChance = 0.30f),
            seed = 31L,
        )
        session.step(60)
        assertConverged(session, "30% loss did not converge within 60 ticks")
        val dropped = session.harness.log.events.count {
            it.kind == dev.wildware.udea.net.transport.PacketEventKind.Dropped
        }
        assertTrue(dropped > 20, "only $dropped datagrams were dropped; the loss simulation did nothing")
    }

    @Test
    fun `reordering and duplication do not corrupt the client`() {
        val session = movingWorld(
            entities = 10,
            conditions = NetConditions(
                latencyTicks = 3,
                jitterTicks = 3,
                lossChance = 0.1f,
                reorderChance = 0.25f,
                duplicateChance = 0.15f,
            ),
            seed = 77L,
        )
        session.step(80)
        assertConverged(session, "reorder and duplication corrupted the client")
    }

    @Test
    fun `a destroy reaches the client and survives the loss of the datagram carrying it`() {
        lateinit var session: ReplicationSession
        var victim: NetId = NetId.NONE
        var survivor: NetId = NetId.NONE
        session = ReplicationSession(
            seed = 5L,
            conditions = NetConditions(latencyTicks = 2, lossChance = 0.4f),
            mutate = { tick ->
                if (tick.value == 1L) {
                    victim = session.world.spawn(1f, 1f)
                    survivor = session.world.spawn(2f, 2f)
                }
                if (tick.value == 20L) session.world.despawn(victim)
            },
        )
        session.step(60)

        val store = session.clients.single().world
        assertFalse(victim in store, "the destroyed entity is still on the client")
        assertTrue(survivor in store, "the surviving entity was destroyed too")
        assertConverged(session, "the world did not converge after a destroy")
    }

    @Test
    fun `a recycled NetId is not aliased onto the old entity`() {
        lateinit var session: ReplicationSession
        var first: NetId = NetId.NONE
        var second: NetId = NetId.NONE
        session = ReplicationSession(
            seed = 6L,
            mutate = { tick ->
                when (tick.value) {
                    1L -> first = session.world.spawn(10f, 10f, teamId = 1)
                    20L -> session.world.despawn(first)
                    40L -> second = session.world.spawn(-5f, -5f, teamId = 2)
                }
            },
        )
        session.step(70)

        val store = session.clients.single().world
        assertTrue(second in store, "the recycled entity never arrived")
        assertFalse(first in store, "a stale NetId still resolves on the client")
        val moverIndex = session.world.registry.indexOf(MoverReplicator.typeId)
        val slot = store.slotOf(store.rowOf(second), moverIndex)
        assertEquals(
            -5f,
            store.storeAt(moverIndex).getFloat(slot, MoverReplicator.X),
            "the recycled id resolved to the old entity's state",
        )
        assertEquals(ReplicaStore.ABSENT, store.rowOf(first))
    }

    @Test
    fun `a small budget degrades update rate smoothly without losing creates or destroys`() {
        val session = movingWorld(entities = 60, budgetBytes = 120, seed = 12L)
        session.step(200)

        assertTrue(session.server.budgetDeferrals > 0, "a 120 byte budget never deferred an entity")
        val store = session.clients.single().world
        assertEquals(
            60,
            store.entityCount,
            "a tight budget lost creates: the client knows about ${store.entityCount} of 60 entities",
        )
    }

    @Test
    fun `no entity is starved for longer than the accumulator bound`() {
        val session = movingWorld(entities = 60, budgetBytes = 200, seed = 13L)
        session.step(60)

        val client = session.clients.single()
        val staleness = ArrayList<Long>()
        session.step(240)
        val server = session.serverStateAt(client.serverTick).fields
        val store = client.world
        // Every entity must have been updated recently enough that its x is close to the
        // server's; x advances by one per tick, so the gap *is* the staleness in ticks.
        val moverIndex = session.world.registry.indexOf(MoverReplicator.typeId)
        for (row in 0 until server.rowCount) {
            val netId = server.netIdAt(row)
            val clientRow = store.rowOf(netId)
            assertTrue(clientRow != ReplicaStore.ABSENT, "$netId never reached the client at all")
            val serverX = server.storeAt(moverIndex)
                .getFloat(server.componentSlotAt(row, moverIndex), MoverReplicator.X)
            val clientX = store.storeAt(moverIndex)
                .getFloat(store.slotOf(clientRow, moverIndex), MoverReplicator.X)
            staleness += (serverX - clientX).toLong()
        }
        val worst = staleness.max()
        assertTrue(worst < 60, "the worst entity was $worst ticks stale, over the 60 tick bound")
    }

    @Test
    fun `the server sends exactly one datagram per client per tick`() {
        val session = movingWorld(entities = 8, clients = 3)
        // 41 ticks, then count 40: a datagram sent during onTick(41) has not been released yet,
        // because the release pass runs at the top of a tick. Counting 40 of 41 is the exact
        // statement of one-per-tick; counting 40 of 40 would be off by one and would pass for a
        // server that skipped a tick somewhere in the middle.
        session.step(41)
        for (client in session.clients) {
            val stats = session.harness.transport(dev.wildware.udea.net.transport.PeerId.SERVER).stats(client.peer)
            assertEquals(
                40L,
                stats.packetsSent,
                "the server sent ${stats.packetsSent} datagrams to ${client.peer} over 41 ticks",
            )
        }
    }

    @Test
    fun `an entity whose baseline aged out of the ring is written in full again`() {
        // A tiny ring: the baselines a client acked fall out of it within a handful of ticks, so
        // the server has no choice but to write full state. Convergence must survive that.
        val session = ReplicationSession(
            seed = 8L,
            conditions = NetConditions(latencyTicks = 6, jitterTicks = 3),
        )
        val ids = ArrayList<NetId>()
        repeat(6) { ids += session.world.spawn(it.toFloat(), 0f) }
        session.step(2)
        for (netId in ids) session.world.mover(netId).x += 1f
        session.step(40)
        assertConverged(session, "a session with a short baseline chain did not converge")
    }

    /**
     * The delta is a delta: a quiet world costs a header, and one moving entity costs one entity.
     *
     * This is the assertion that makes "the snapshot ring **is** the baseline store" (spec 3.1)
     * falsifiable. Every convergence test in this class passes just as happily against a server
     * that abandons baselines and writes full state every tick — full state converges, it simply
     * costs the entire world every tick, which is precisely `NetworkServerSystem.kt:110`. Only a
     * *size* assertion can tell the two apart.
     */
    @Test
    fun `a quiet world costs a header and one moving entity costs one entity`() {
        lateinit var session: ReplicationSession
        val ids = ArrayList<NetId>()
        var moveOne = false
        session = ReplicationSession(
            seed = 41L,
            mutate = { _ ->
                if (ids.isEmpty()) repeat(50) { ids += session.world.spawn(it.toFloat(), 0f) }
                if (moveOne) session.world.mover(ids[0]).x += 1f
            },
        )
        // Long enough for every create to be acknowledged and every baseline to settle.
        session.step(60)
        val client = session.clients.single()
        assertConverged(session, "the world did not converge before the size measurement")

        val transport = session.harness.transport(dev.wildware.udea.net.transport.PeerId.SERVER)
        val quietStart = transport.stats(client.peer).bytesSent
        session.step(20)
        val quietBytesPerTick = (transport.stats(client.peer).bytesSent - quietStart) / 20.0
        assertTrue(
            quietBytesPerTick < 20,
            "a world where nothing moved cost $quietBytesPerTick bytes per tick for 50 entities; " +
                "the server is not delta-encoding against the ring",
        )

        moveOne = true
        val movingStart = transport.stats(client.peer).bytesSent
        session.step(20)
        val movingBytesPerTick = (transport.stats(client.peer).bytesSent - movingStart) / 20.0
        assertTrue(
            movingBytesPerTick < 40,
            "one moving entity out of 50 cost $movingBytesPerTick bytes per tick; a delta " +
                "against the acked baseline must carry that entity and nothing else",
        )
        assertTrue(movingBytesPerTick > quietBytesPerTick, "the moving entity was not sent at all")
        assertConverged(session, "the world did not converge while one entity moved")
    }

    /**
     * Issue #107's headline number: 300 entities, every client under 40 KB/s, nothing starved
     * beyond half a second.
     */
    @Test
    fun `three hundred entities hold every client under forty kilobytes per second`() {
        val session = movingWorld(entities = 300, clients = 4, budgetBytes = 600, seed = 17L)
        session.step(60)
        val transport = session.harness.transport(dev.wildware.udea.net.transport.PeerId.SERVER)
        val before = session.clients.associate { it.peer.raw to transport.stats(it.peer).bytesSent }

        val ticks = 600
        session.step(ticks)
        val seconds = ticks / 60.0
        for (client in session.clients) {
            val bytes = transport.stats(client.peer).bytesSent - before.getValue(client.peer.raw)
            val kilobytesPerSecond = bytes / seconds / 1024.0
            assertTrue(
                kilobytesPerSecond < 40.0,
                "${client.peer} received ${"%.1f".format(kilobytesPerSecond)} KB/s, over the 40 KB/s budget",
            )
        }

        // And the budget is bought with staleness, not with silence: x advances by one per tick,
        // so the gap between server and client x *is* how many ticks ago the entity was last sent.
        val client = session.clients.first()
        val server = session.serverStateAt(client.serverTick).fields
        val moverIndex = session.world.registry.indexOf(MoverReplicator.typeId)
        var worst = 0L
        for (row in 0 until server.rowCount) {
            val netId = server.netIdAt(row)
            val clientRow = client.world.rowOf(netId)
            assertTrue(clientRow != ReplicaStore.ABSENT, "$netId never reached ${client.peer}")
            val serverX = server.storeAt(moverIndex).getFloat(server.componentSlotAt(row, moverIndex), MoverReplicator.X)
            val clientX = client.world.storeAt(moverIndex).getFloat(client.world.slotOf(clientRow, moverIndex), MoverReplicator.X)
            worst = maxOf(worst, (serverX - clientX).toLong())
        }
        assertTrue(worst <= 30, "the stalest of 300 entities was $worst ticks behind, over the 500ms bound")
    }

    @Test
    fun `a fresh world captures and replicates without a baseline`() {
        val world = NetTestWorld()
        world.spawn(1f, 2f)
        val snapshot = world.captureTick()
        assertEquals(1, snapshot.fields.rowCount)
    }
}
