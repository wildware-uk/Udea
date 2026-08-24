package dev.wildware.udea.net.replication

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.harness.Vitals
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.PacketEventKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exact agreement at 300ms, 20% loss, reordering and duplication — over twenty seeds.
 *
 * "Exact" is the bar, not "eventual": every replicated field of every entity is compared against
 * the server's ring slot for **the tick the client actually holds**, with no drain period and no
 * settling time. Twenty seeds because the defect this covers — a field that changed and changed
 * back inside the acknowledgement window — is a race whose window widens with latency, so a
 * single seeded run that passes says almost nothing. The seed is printed in every failure, and
 * `SimulatedTransport` draws from the seeded `RngService`, so a red run reproduces exactly.
 *
 * The world is deliberately hostile to a delta encoder: fields that oscillate on periods that do
 * not divide the round trip, entities spawning and despawning so `NetId`s recycle, and components
 * added and dropped from live entities.
 */
class HarshLinkConvergenceTest {

    @Test
    fun `every field agrees exactly at 300ms and 20 percent loss with reordering, over twenty seeds`() {
        var dropped = 0
        var duplicated = 0
        var outOfOrder = 0L
        for (seed in 0 until SEEDS) {
            val session = churningWorld(SEED_BASE + seed)
            session.step(CHURN_TICKS)
            session.step(TOTAL_TICKS - CHURN_TICKS)

            val client = session.clients.single()
            assertTrue(client.applied > 50, "seed ${SEED_BASE + seed}: the client applied only ${client.applied} packets")
            val server = session.serverStateAt(client.serverTick).fields
            val report = DesyncReport.compare(session.world.registry, server, client.world)
            assertTrue(
                report.isEmpty(),
                "seed ${SEED_BASE + seed}: 300ms/20%/reorder desynced ${report.size} field(s) at " +
                    "tick ${client.serverTick}:\n" + report.take(12).joinToString("\n"),
            )
            dropped += session.harness.log.events.count { it.kind == PacketEventKind.Dropped }
            duplicated += session.harness.log.events.count { it.kind == PacketEventKind.Duplicated }
            outOfOrder += client.staleDropped
        }
        // Without this the whole suite is satisfied by an impairment that never impaired anything.
        assertTrue(dropped > 20 * SEEDS, "only $dropped datagrams were dropped across $SEEDS seeds")
        assertTrue(duplicated > SEEDS, "only $duplicated datagrams were duplicated across $SEEDS seeds")
        assertTrue(
            outOfOrder > SEEDS,
            "only $outOfOrder datagrams arrived out of order across $SEEDS seeds; reordering did nothing",
        )
    }

    private fun churningWorld(seed: Long): ReplicationSession {
        lateinit var session: ReplicationSession
        val live = ArrayList<NetId>()
        session = ReplicationSession(
            seed = seed,
            conditions = NetConditions(
                latencyTicks = 18,
                jitterTicks = 4,
                lossChance = 0.20f,
                reorderChance = 0.25f,
                duplicateChance = 0.10f,
            ),
            mutate = { tick ->
                val t = tick.value
                if (t == 1L) repeat(RESIDENT) { live += session.world.spawn(it.toFloat(), 0f, teamId = it % 2) }

                if (t in 2 until CHURN_TICKS) {
                    // Recycle a NetId every so often, so a create, a destroy and a generation
                    // bump are all in flight while the oscillating fields are moving.
                    if (t % 37L == 0L && live.size > RESIDENT / 2) session.world.despawn(live.removeAt(0))
                    if (t % 41L == 0L) live += session.world.spawn(-t.toFloat(), t.toFloat(), teamId = 1)
                    // ...and add and drop a whole component from a live entity, which is the
                    // other thing a delta encoder has to be able to say.
                    if (t % 23L == 0L && live.isNotEmpty()) toggleVitals(session.world, live[(t / 23L % live.size).toInt()])
                }

                for ((index, netId) in live.withIndex()) {
                    val mover = session.world.mover(netId)
                    mover.x = (t + index).toFloat()
                    mover.y = -(t * 3 + index).toFloat()
                    val vitals = vitalsOrNull(session.world, netId) ?: continue
                    // Periods 3 and 7: neither divides the round trip, so the "equal at the
                    // baseline and equal now, different in between" case happens constantly.
                    vitals.hp = 100 - ((t + index) % 3L).toInt() * 5
                    vitals.shielded = (t + index) % 7L < 3L
                }
            },
        )
        return session
    }

    private fun toggleVitals(world: NetTestWorld, netId: NetId) {
        val entity = world.netIds.resolveOrNull(netId) ?: return
        with(world.world) {
            if (entity has Vitals) entity.configure { it -= Vitals } else entity.configure { it += Vitals() }
        }
    }

    private fun vitalsOrNull(world: NetTestWorld, netId: NetId): Vitals? {
        val entity = world.netIds.resolveOrNull(netId) ?: return null
        return with(world.world) { if (entity has Vitals) entity[Vitals] else null }
    }

    private companion object {
        const val SEEDS = 20
        const val SEED_BASE = 20_260_823L
        const val RESIDENT = 24
        const val CHURN_TICKS = 300
        const val TOTAL_TICKS = 420
    }
}
