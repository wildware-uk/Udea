package dev.wildware.udea.core.replication

import dev.wildware.udea.core.fixtures.ArrayBitWriter
import dev.wildware.udea.core.fixtures.ArrayFieldStore
import dev.wildware.udea.core.fixtures.Targeting
import dev.wildware.udea.core.fixtures.TargetingReplicator
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * A [NetId] is a primitive field type, not a special case (spec 5, "Entity identity").
 *
 * An entity-referencing field has to survive capture, delta write and restore through exactly
 * the same path as a float. If it needed a special case anywhere, that special case would be
 * where the wire format, the snapshot ring and the agent surface start to disagree about what
 * an entity reference is.
 */
class NetIdFieldTest {

    private val replicator = TargetingReplicator

    private fun store() = ArrayFieldStore(slotCount = 3, fieldCount = TargetingReplicator.FIELD_COUNT)

    @Test
    fun `a NetId field round-trips through capture, write, read and apply`() {
        val source = Targeting(target = NetId.of(1234, 7), lastAttacker = NetId.of(9, 255))
        val store = store()

        replicator.capture(source, store, CAPTURED)
        val mask = replicator.diff(store, CAPTURED, BASELINE)
        assertEquals(replicator.allMask, mask)

        val writer = ArrayBitWriter()
        replicator.write(store, CAPTURED, mask, writer)
        assertEquals(replicator.read(writer.toReader(), store, RECEIVED), mask)

        val destination = Targeting()
        replicator.apply(store, RECEIVED, destination, mask)

        assertEquals(NetId.of(1234, 7), destination.target)
        assertEquals(NetId.of(9, 255), destination.lastAttacker)
    }

    @Test
    fun `NONE round-trips like any other id`() {
        val store = store()
        replicator.capture(Targeting(target = NetId.of(3, 0)), store, BASELINE)
        replicator.capture(Targeting(target = NetId.NONE), store, CAPTURED)

        val delta = replicator.diff(store, CAPTURED, BASELINE)
        assertEquals(MaskOps.of(TargetingReplicator.FIELD_TARGET), delta)

        val writer = ArrayBitWriter()
        replicator.write(store, CAPTURED, delta, writer)
        replicator.read(writer.toReader(), store, RECEIVED)

        val destination = Targeting(target = NetId.of(3, 0))
        replicator.apply(store, RECEIVED, destination, delta)
        assertEquals(NetId.NONE, destination.target)
    }

    @Test
    fun `a stale generation is a change, so a retarget is replicated`() {
        // Two ids at the same index differing only in generation are different entities. If
        // the store dropped the generation, a retarget onto a recycled slot would look like
        // no change at all and never reach the client.
        val store = store()
        replicator.capture(Targeting(target = NetId.of(5, 0)), store, BASELINE)
        replicator.capture(Targeting(target = NetId.of(5, 1)), store, CAPTURED)

        assertEquals(
            MaskOps.of(TargetingReplicator.FIELD_TARGET),
            replicator.diff(store, CAPTURED, BASELINE),
        )
        assertFalse(store.fieldEquals(CAPTURED, BASELINE, TargetingReplicator.FIELD_TARGET))
    }

    @Test
    fun `a Sim entity reference is snapshotted but not replicated`() {
        val store = store()
        val source = Targeting(target = NetId.of(1, 0), lastAttacker = NetId.of(2, 0))
        replicator.capture(source, store, CAPTURED)

        val netDelta = MaskOps.and(replicator.diff(store, CAPTURED, BASELINE), replicator.netMask)
        assertEquals(MaskOps.of(TargetingReplicator.FIELD_TARGET), netDelta)

        val restored = Targeting()
        replicator.apply(store, CAPTURED, restored, replicator.allMask)
        assertEquals(NetId.of(2, 0), restored.lastAttacker, "a snapshot carries the @Sim reference")
    }

    @Test
    fun `getField and setField speak NetId`() {
        val targeting = Targeting()

        replicator.setField(targeting, TargetingReplicator.FIELD_TARGET, NetId.of(11, 2))
        replicator.setField(targeting, TargetingReplicator.FIELD_LAST_ATTACKER, NetId.of(12, 3))

        assertEquals(NetId.of(11, 2), replicator.getField(targeting, TargetingReplicator.FIELD_TARGET))
        assertEquals(
            NetId.of(12, 3),
            replicator.getField(targeting, TargetingReplicator.FIELD_LAST_ATTACKER),
        )
        assertFailsWith<IllegalArgumentException> {
            replicator.setField(targeting, TargetingReplicator.FIELD_TARGET, 11)
        }
    }

    @Test
    fun `the store keeps NetId whole, generation included`() {
        val store = store()
        val id = NetId.of(65_535, 255)

        store.setNetId(CAPTURED, TargetingReplicator.FIELD_TARGET, id)

        assertEquals(id, store.getNetId(CAPTURED, TargetingReplicator.FIELD_TARGET))
        assertEquals(65_535, store.getNetId(CAPTURED, TargetingReplicator.FIELD_TARGET).index)
        assertEquals(255, store.getNetId(CAPTURED, TargetingReplicator.FIELD_TARGET).generation)
    }

    private companion object {
        const val BASELINE: Int = 0
        const val CAPTURED: Int = 1
        const val RECEIVED: Int = 2
    }
}
