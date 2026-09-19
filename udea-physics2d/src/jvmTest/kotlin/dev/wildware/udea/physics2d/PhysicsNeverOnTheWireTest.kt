package dev.wildware.udea.physics2d

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.physics.Teleport
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.wire.EntityOp
import dev.wildware.udea.net.wire.ReplicaStore
import dev.wildware.udea.net.wire.SnapshotReader
import dev.wildware.udea.net.wire.SnapshotWriter
import dev.wildware.udea.physics2d.Box2DScene.Companion.buildStandardScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No physics field ever reaches a delta packet.
 *
 * Every physics field is `@Sim`, so snapshots carry it and the wire does not. This drives the
 * real packet encoder, `udea-net`'s `SnapshotWriter`, over the real captures of a scene whose
 * bodies are falling and colliding - the case where every physics field changes on every tick -
 * as the owner of every entity, the recipient who is shown the most - and counts the bits.
 *
 * One record is written, and the second test pins it rather than pretending otherwise: when a
 * `@Sim`-only component leaves an entity that is still alive, `SnapshotWriter` writes its component
 * id with an empty field mask, as it does for every `@Sim`-only component. That test reads the bits
 * back with `SnapshotReader` to show the record names the component and carries no field.
 */
class PhysicsNeverOnTheWireTest {

    @Test
    fun `creates and deltas of moving bodies write no physics bits at all`() {
        Box2DScene().use { scene ->
            scene.buildStandardScene()
            scene.step()
            val writer = SnapshotWriter(scene.registry)
            val out = BitBufferWriter(ByteArray(BUFFER_BYTES))
            val terminatorBits = sectionBits(writer, out) {}

            var previous = scene.snapshots.capture()
            val createBits = sectionBits(writer, out) {
                for (row in 0 until previous.fields.rowCount) {
                    assertEquals(0, writer.writeCreate(out, previous.fields, row, recipientOwnsEntity = true))
                }
            }
            assertEquals(terminatorBits, createBits, "a create carried physics")

            var movedRows = 0
            repeat(TICKS) { tick ->
                val before = positions(scene)
                scene.step()
                movedRows += moved(before, positions(scene))
                val current = scene.snapshots.capture()
                val updateBits = sectionBits(writer, out) {
                    for (row in 0 until current.fields.rowCount) {
                        val baselineRow = previous.fields.rowOf(current.fields.netIdAt(row))
                        assertEquals(
                            0,
                            writer.writeUpdate(out, current.fields, row, previous.fields, baselineRow, recipientOwnsEntity = true),
                            "tick $tick row $row",
                        )
                    }
                }
                assertEquals(terminatorBits, updateBits, "tick $tick: a delta carried physics")
                previous = current
            }
            assertTrue(movedRows > TICKS * 5, "the bodies were not moving ($movedRows changed rows), so no delta was tested")
        }
    }

    @Test
    fun `a physics component leaving a live entity is named with an empty mask and nothing else`() {
        Box2DScene().use { scene ->
            scene.buildStandardScene()
            scene.step()
            var id = NetId.NONE
            scene.netIds.forEachLive { netId, _ -> if (id.isNone) id = netId }
            // Present in the baseline, consumed by the next tick's TeleportSystem.
            with(scene.world) { scene.entityOf(id).configure { it += Teleport(x = 1f, y = 1f) } }
            val baseline = scene.snapshots.capture()
            scene.step()
            val current = scene.snapshots.capture()

            val writer = SnapshotWriter(scene.registry)
            val out = BitBufferWriter(ByteArray(BUFFER_BYTES))
            writer.begin()
            val row = current.fields.rowOf(id)
            assertEquals(1, writer.writeUpdate(out, current.fields, row, baseline.fields, baseline.fields.rowOf(id), true))
            writer.end(out)

            val store = ReplicaStore(scene.registry).also { it.createRow(id) }
            val applied = ArrayList<String>()
            SnapshotReader(scene.registry).read(out.toReader(), store) { netId, op, componentIndex, mask ->
                applied += "$netId $op ${scene.registry.schemaAt(componentIndex).typeName} empty=${MaskOps.isEmpty(mask)}"
            }

            assertEquals(listOf("$id ${EntityOp.Update} Teleport empty=true"), applied)
            for (component in 0 until scene.registry.size) {
                assertEquals(ReplicaStore.ABSENT, store.slotOf(store.rowOf(id), component), "the client holds no physics field")
            }
        }
    }

    /** Bits one whole section takes, `begin` to `end`, with [body] writing its records. */
    private fun sectionBits(writer: SnapshotWriter, out: BitBufferWriter, body: () -> Unit): Long {
        out.reset()
        writer.begin()
        body()
        writer.end(out)
        return out.bitsWritten
    }

    /** Every live body's position, in ascending `NetId`. */
    private fun positions(scene: Box2DScene): FloatArray {
        val out = ArrayList<Float>()
        scene.netIds.forEachLive { _, entity ->
            val body = with(scene.world) { entity[PhysicsBody] }
            out += body.x
            out += body.y
        }
        return out.toFloatArray()
    }

    /** How many bodies moved between two [positions] readings. */
    private fun moved(before: FloatArray, after: FloatArray): Int =
        (before.indices step 2).count { before[it] != after[it] || before[it + 1] != after[it + 1] }

    private companion object {
        const val TICKS = 120
        const val BUFFER_BYTES = 64 * 1024
    }
}
