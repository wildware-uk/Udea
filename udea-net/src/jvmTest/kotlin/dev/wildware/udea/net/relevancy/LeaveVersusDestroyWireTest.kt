package dev.wildware.udea.net.relevancy

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.harness.NetTestComponents
import dev.wildware.udea.net.wire.EntityOp
import dev.wildware.udea.net.wire.ReplicaStore
import dev.wildware.udea.net.wire.SnapshotReader
import dev.wildware.udea.net.wire.SnapshotWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Leaving relevancy and ceasing to exist are different things on the wire.
 *
 * Spec 7 names this as one of the two hard parts, and it names the symptom too: a client that
 * treats leaving relevancy as death plays a death animation for a unit that walked into a bush.
 * The defence has to be structural rather than a convention, so it is two distinct op codes that
 * survive a round trip and arrive at the apply sink separately labelled. `ReplicaStore` drops the
 * row for both — a client holds no state for something it cannot see, which is the anti-cheat
 * property — and the **op** is what tells the ECS layer above whether to play a death or a fade.
 *
 * The fog side of the same distinction is asserted in [FogOfWarTest]: an entity that walks out of
 * vision lands on the leave list and an entity that stops existing does not.
 */
class LeaveVersusDestroyWireTest {

    private val registry = NetTestComponents.registry()

    @Test
    fun `leave and destroy arrive at the sink as different ops`() {
        val leaver = NetId.of(5, 0)
        val dead = NetId.of(9, 3)
        val bytes = write { writer, section ->
            section.writeRemoval(writer, leaver, EntityOp.Leave)
            section.writeRemoval(writer, dead, EntityOp.Destroy)
        }

        val applied = read(bytes)

        assertEquals(listOf(leaver to EntityOp.Leave, dead to EntityOp.Destroy), applied)
        assertNotEquals(EntityOp.Leave.code, EntityOp.Destroy.code, "two ops that share a code are one op")
    }

    @Test
    fun `a leave drops the client's row, so nothing about the entity is left in the process`() {
        val leaver = NetId.of(4, 1)
        val bytes = write { writer, section -> section.writeRemoval(writer, leaver, EntityOp.Leave) }
        val store = ReplicaStore(registry)
        store.createRow(leaver)
        assertNotEquals(ReplicaStore.ABSENT, store.rowOf(leaver))

        SnapshotReader(registry).read(BitBufferReader(bytes, 0, bytes.size), store)

        assertEquals(
            ReplicaStore.ABSENT,
            store.rowOf(leaver),
            "hiding an entity while keeping its row is client-side fog, which spec 3 rejects",
        )
    }

    @Test
    fun `a removal op that is neither leave nor destroy is refused by name`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            write { writer, section -> section.writeRemoval(writer, NetId.of(1, 0), EntityOp.Update) }
        }
        assertEquals("Update is not a removal", failure.message)
    }

    private fun write(body: (BitBufferWriter, SnapshotWriter) -> Unit): ByteArray {
        val buffer = ByteArray(BUFFER_BYTES)
        val writer = BitBufferWriter(buffer)
        val section = SnapshotWriter(registry)
        section.begin()
        body(writer, section)
        section.end(writer)
        return buffer.copyOf(writer.byteLength)
    }

    private fun read(bytes: ByteArray): List<Pair<NetId, EntityOp>> {
        val applied = mutableListOf<Pair<NetId, EntityOp>>()
        SnapshotReader(registry).read(BitBufferReader(bytes, 0, bytes.size), ReplicaStore(registry)) {
                netId, op, _, _ ->
            applied += netId to op
        }
        return applied
    }

    private companion object {

        /** More than enough for a handful of removal records. */
        const val BUFFER_BYTES: Int = 256
    }
}
