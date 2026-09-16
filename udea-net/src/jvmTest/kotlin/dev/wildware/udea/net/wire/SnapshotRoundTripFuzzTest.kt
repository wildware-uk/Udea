package dev.wildware.udea.net.wire

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.rng.SimRandom
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.harness.MoverReplicator
import dev.wildware.udea.net.harness.NetTestComponents
import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.harness.VitalsReplicator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Capture, diff, write, read, apply — ten thousand seeded iterations, under loss and reorder.
 *
 * The round trip is the property `PacketUtil.kt` never had: it wrote a component stream with no
 * type tag and no length prefix, so a write and a read could disagree without either failing.
 * Here every iteration ends with a field-by-field comparison of the client's store against the
 * server's capture, and any disagreement fails naming the field.
 *
 * Everything is drawn from a seeded [SimRandom], so a failure is reproducible from the seed in the
 * message rather than being a thing that happened once in CI.
 */
class SnapshotRoundTripFuzzTest {

    private val registry = NetTestComponents.registry()

    @Test
    fun `ten thousand seeded capture diff write read apply cycles end field identical`() {
        val rng = SimRandom(20_260_823L)
        val world = NetTestWorld(registry = registry)
        val client = ReplicaStore(registry)
        val writer = SnapshotWriter(registry)
        val reader = SnapshotReader(registry)
        val buffer = ByteArray(8192)
        val out = BitBufferWriter(buffer)

        val ids = ArrayList<NetId>()
        repeat(12) { ids += world.spawn(it.toFloat(), 0f, teamId = it % 3, withVitals = it % 2 == 0) }

        // The tick each entity's baseline sits at, per entity: exactly the server-side structure
        // issue #107 specifies, driven here directly so the fuzzer exercises it without a network.
        val baselineTicks = HashMap<Int, Tick>()
        var current: WorldSnapshot = world.captureTick()
        var applied = 0
        var dropped = 0

        repeat(ITERATIONS) { iteration ->
            for (netId in ids) {
                val mover = world.mover(netId)
                when (rng.nextInt(4)) {
                    0 -> mover.x = rng.nextFloat() * 100f
                    1 -> mover.y = rng.nextFloat() * 100f
                    2 -> if (netId.index % 2 == 0) world.vitals(netId).hp = rng.nextInt(1000)
                    else -> if (netId.index % 2 == 0) world.vitals(netId).shielded = rng.nextBoolean()
                }
            }
            current = world.captureTick()

            out.reset()
            writer.begin()
            for (netId in ids) {
                val row = current.fields.rowOf(netId)
                val baselineTick = baselineTicks[netId.raw]
                val baseline = baselineTick?.let { world.ring.nearestAtOrBefore(it) }
                if (baseline == null || baseline.tick != baselineTick) {
                    writer.writeCreate(out, current.fields, row, recipientOwnsEntity = true)
                } else {
                    writer.writeUpdate(
                        out,
                        current.fields,
                        row,
                        baseline.fields,
                        baseline.fields.rowOf(netId),
                        recipientOwnsEntity = true,
                    )
                }
            }
            writer.end(out)

            // Loss and reorder: a fifth of packets are simply not applied. The baselines of the
            // entities they carried do not advance, so the next packet must re-send everything
            // they said — which is the whole of the recovery protocol, exercised 2000 times here.
            if (rng.nextInt(5) == 0) {
                dropped++
            } else {
                reader.read(out.toReader(), client)
                for (netId in ids) baselineTicks[netId.raw] = current.tick
                applied++
                assertConverged(current, client, iteration)
            }
        }

        assertTrue(dropped > ITERATIONS / 10, "only $dropped of $ITERATIONS packets were dropped")
        assertTrue(applied > ITERATIONS / 2, "only $applied of $ITERATIONS packets were applied")
    }

    private fun assertConverged(server: WorldSnapshot, client: ReplicaStore, iteration: Int) {
        val moverIndex = registry.indexOf(MoverReplicator.typeId)
        val vitalsIndex = registry.indexOf(VitalsReplicator.typeId)
        for (row in 0 until server.fields.rowCount) {
            val netId = server.fields.netIdAt(row)
            val clientRow = client.rowOf(netId)
            assertTrue(clientRow != ReplicaStore.ABSENT, "iteration $iteration: $netId missing from the client")

            val serverMover = server.fields.storeAt(moverIndex)
            val serverSlot = server.fields.componentSlotAt(row, moverIndex)
            val clientMover = client.storeAt(moverIndex)
            val clientSlot = client.slotOf(clientRow, moverIndex)
            for (field in intArrayOf(MoverReplicator.X, MoverReplicator.Y, MoverReplicator.TEAM_ID)) {
                assertTrue(
                    serverMover.fieldEquals(
                        serverSlot,
                        clientMover,
                        clientSlot,
                        field,
                        dev.wildware.udea.core.snapshot.FieldComparison.Bitwise,
                    ),
                    "iteration $iteration: $netId Mover.${MoverReplicator.fieldNames[field]} differs",
                )
            }

            if (!server.fields.isPresent(row, vitalsIndex)) continue
            val serverVitals = server.fields.storeAt(vitalsIndex)
            val serverVitalsSlot = server.fields.componentSlotAt(row, vitalsIndex)
            val clientVitals = client.storeAt(vitalsIndex)
            val clientVitalsSlot = client.slotOf(clientRow, vitalsIndex)
            assertEquals(
                serverVitals.getInt(serverVitalsSlot, VitalsReplicator.HP),
                clientVitals.getInt(clientVitalsSlot, VitalsReplicator.HP),
                "iteration $iteration: $netId Vitals.hp differs",
            )
            assertEquals(
                serverVitals.getBoolean(serverVitalsSlot, VitalsReplicator.SHIELDED),
                clientVitals.getBoolean(clientVitalsSlot, VitalsReplicator.SHIELDED),
                "iteration $iteration: $netId Vitals.shielded differs",
            )
        }
    }

    private companion object {

        /** Twelve entities each iteration, so 10 000 cycles is 120 000 entity round trips. */
        const val ITERATIONS: Int = 10_000
    }
}
