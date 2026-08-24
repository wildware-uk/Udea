package dev.wildware.udea.net.replication

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.transport.NetConditions
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A replicated field that changes and changes back inside the acknowledgement window.
 *
 * Every other convergence test in this module moves entities *monotonically* — `x = tick + i` —
 * so a field the packer omits because it equals the acked baseline also equals whatever the
 * client is holding, and the delta chain looks correct while resting on an assumption that is
 * not true. An oscillating field is the case that separates the two: the server diffs against
 * the newest **acked** packet, the client holds the newest **applied** one, and those are a
 * round trip apart.
 */
class AckWindowConvergenceTest {

    private fun oscillating(conditions: NetConditions, seed: Long, entities: Int = 6): ReplicationSession {
        lateinit var session: ReplicationSession
        val ids = ArrayList<NetId>()
        session = ReplicationSession(
            seed = seed,
            conditions = conditions,
            mutate = { tick ->
                if (ids.isEmpty()) repeat(entities) { ids += session.world.spawn(it.toFloat(), 0f) }
                for ((index, netId) in ids.withIndex()) {
                    val vitals = session.world.vitals(netId)
                    vitals.hp = 100 - ((tick.value + index) % 3L).toInt() * 5
                    vitals.shielded = (tick.value + index) % 5L < 2L
                }
            },
        )
        return session
    }

    private fun assertConverged(session: ReplicationSession, what: String) {
        for (client in session.clients) {
            assertTrue(client.applied > 0, "${client.peer} never applied a packet")
            val server = session.serverStateAt(client.serverTick).fields
            val report = DesyncReport.compare(session.world.registry, server, client.world)
            assertTrue(report.isEmpty(), "$what — ${client.peer} differs:\n" + report.joinToString("\n"))
        }
    }

    @Test
    fun `an oscillating field converges across a latent link`() {
        val session = oscillating(NetConditions(latencyTicks = 4, jitterTicks = 0), seed = 101L)
        session.step(60)
        assertConverged(session, "latency alone desynced an oscillating field")
    }

    @Test
    fun `an oscillating field converges under loss`() {
        val session = oscillating(NetConditions(latencyTicks = 4, jitterTicks = 2, lossChance = 0.2f), seed = 202L)
        session.step(120)
        assertConverged(session, "loss desynced an oscillating field")
    }
}
