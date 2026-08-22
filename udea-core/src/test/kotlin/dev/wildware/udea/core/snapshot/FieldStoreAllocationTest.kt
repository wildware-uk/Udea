package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.alloc.AllocationProbe
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The store's central claim: once warm, writing every field of every row allocates nothing.
 *
 * Spec 7 makes this a hard gate rather than an aspiration, and gives the reason in one line —
 * one structure carries time travel, replication baselines and rollback, so if capture
 * allocates then three features degrade at once. The old engine's equivalent path,
 * `NetworkServerSystem.kt:110`, built one `EntityUpdate` per entity per tick: 60 000 objects a
 * second at 1000 entities.
 */
class FieldStoreAllocationTest {

    @Test
    fun `a warm full write pass over the store allocates zero bytes`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")

        val store = ColumnarFieldStore(TestComponents.vitalsSchema, initialSlots = ROWS)

        // The first pass is the warm-up the probe discards; the measured passes come after the
        // JIT has compiled the write loop, which is the only state the engine ever runs in.
        val allocated = AllocationProbe.bytesAllocated { writeEveryField(store) }

        assertEquals(
            SnapshotBudgets.CAPTURE_ALLOCATED_BYTES,
            allocated,
            "a full write pass allocated $allocated bytes; the budget is zero",
        )
    }

    @Test
    fun `reset then refill allocates zero bytes, which is what pooling a ring slot means`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")

        val store = ColumnarFieldStore(TestComponents.vitalsSchema, initialSlots = ROWS)

        val allocated = AllocationProbe.bytesAllocated {
            store.reset()
            writeEveryField(store)
        }

        assertEquals(0L, allocated, "reset-and-refill allocated $allocated bytes")
    }

    @Test
    fun `an object column's write pass allocates nothing when the values are already interned`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")

        val store = ColumnarFieldStore(TestComponents.linkSchema, initialSlots = ROWS)
        val squads = arrayOf<Any?>("squad.red", "squad.blue", null)

        val allocated = AllocationProbe.bytesAllocated {
            var slot = 0
            while (slot < ROWS) {
                store.setNetId(slot, LinkReplicator.TARGET, NetId.NONE)
                store.setObject(slot, LinkReplicator.SQUAD, squads[slot % 3])
                slot++
            }
        }

        assertEquals(0L, allocated, "an object column write pass allocated $allocated bytes")
    }

    @Test
    fun `a diff over two warm stores allocates nothing`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")

        val registry = TestComponents.registry()
        val left = buildStore(registry, entities = 200)
        val right = buildStore(registry, entities = 200)
        val diff = FieldDiff(initialCapacity = 4_096)

        val allocated = AllocationProbe.bytesAllocated { left.diffInto(right, diff) }

        assertEquals(0L, allocated, "diffInto allocated $allocated bytes")
    }

    private companion object {

        const val ROWS: Int = 1_000

        fun writeEveryField(store: ColumnarFieldStore) {
            var slot = 0
            while (slot < ROWS) {
                store.setFloat(slot, VitalsReplicator.HEALTH, slot * 0.25f)
                store.setInt(slot, VitalsReplicator.SHIELD_CHARGES, slot)
                store.setBoolean(slot, VitalsReplicator.INVULNERABLE, slot and 1 == 0)
                store.setLong(slot, VitalsReplicator.DAMAGE_DEALT, slot.toLong())
                store.setTick(slot, VitalsReplicator.RESPAWN_TICK, Tick(slot.toLong()))
                slot++
            }
        }

        /** A world store with [entities] rows carrying every component. */
        fun buildStore(registry: ComponentRegistry, entities: Int): WorldFieldStore {
            val fields = WorldFieldStore(registry, initialRows = entities)
            for (index in 0 until entities) {
                val netId = NetId.of(index, 0)
                val row = fields.appendRow(netId)
                for (component in 0 until registry.size) {
                    val slot = fields.claimSlot(row, component)
                    val store = fields.storeAt(component)
                    for (field in 0 until store.fieldCount) {
                        writeSomething(store, slot, field, index)
                    }
                }
            }
            return fields
        }

        fun writeSomething(store: ColumnarFieldStore, slot: Int, field: Int, seed: Int) {
            when (store.schema.kindOf(field)) {
                FieldKind.Bool -> store.setBoolean(slot, field, seed and 1 == 0)
                FieldKind.Int -> store.setInt(slot, field, seed)
                FieldKind.Long -> store.setLong(slot, field, seed.toLong())
                FieldKind.Float -> store.setFloat(slot, field, seed.toFloat())
                FieldKind.NetId -> store.setNetId(slot, field, NetId.of(seed, 0))
                FieldKind.Tick -> store.setTick(slot, field, Tick(seed.toLong()))
                FieldKind.Object -> store.setObject(slot, field, SnapshotWorld.SQUAD_RED)
            }
        }
    }
}
