package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The columnar store, exercised over every column type it has.
 *
 * `Vitals` covers `Float`, `Int`, `Bool`, `Long` and `Tick`; `Link` covers `NetId` and the
 * object column. Between them that is every [FieldKind], which matters because each kind lands
 * in a different backing array at a different stride and a store that only ever saw floats
 * would never exercise the layout arithmetic at all.
 */
class ColumnarFieldStoreTest {

    @Test
    fun `every primitive column round-trips exactly across ten thousand rows`() {
        val store = ColumnarFieldStore(TestComponents.vitalsSchema, initialSlots = 8)
        store.ensureSlots(ROWS)

        for (slot in 0 until ROWS) {
            store.setFloat(slot, VitalsReplicator.HEALTH, healthAt(slot))
            store.setInt(slot, VitalsReplicator.SHIELD_CHARGES, intAt(slot))
            store.setBoolean(slot, VitalsReplicator.INVULNERABLE, slot % 3 == 0)
            store.setLong(slot, VitalsReplicator.DAMAGE_DEALT, longAt(slot))
            store.setTick(slot, VitalsReplicator.RESPAWN_TICK, Tick(longAt(slot) / 3))
        }

        for (slot in 0 until ROWS) {
            // Raw bits, not `==`: the store's contract is representational equality, and at
            // slot 1 and slot 2 the values are NaN and -0.0f, which `==` gets wrong both ways.
            assertEquals(
                healthAt(slot).toRawBits(),
                store.getFloat(slot, VitalsReplicator.HEALTH).toRawBits(),
                "health at slot $slot",
            )
            assertEquals(intAt(slot), store.getInt(slot, VitalsReplicator.SHIELD_CHARGES))
            assertEquals(slot % 3 == 0, store.getBoolean(slot, VitalsReplicator.INVULNERABLE))
            assertEquals(longAt(slot), store.getLong(slot, VitalsReplicator.DAMAGE_DEALT))
            assertEquals(Tick(longAt(slot) / 3), store.getTick(slot, VitalsReplicator.RESPAWN_TICK))
        }
    }

    @Test
    fun `NetId and object columns round-trip, and an object column holds the caller's reference`() {
        val store = ColumnarFieldStore(TestComponents.linkSchema, initialSlots = 8)
        store.ensureSlots(ROWS)

        val squads = arrayOf("squad.red", "squad.blue", null)
        for (slot in 0 until ROWS) {
            store.setNetId(slot, LinkReplicator.TARGET, netIdAt(slot))
            store.setNetId(slot, LinkReplicator.LAST_ATTACKER, if (slot % 5 == 0) NetId.NONE else netIdAt(slot + 1))
            store.setObject(slot, LinkReplicator.SQUAD, squads[slot % squads.size])
        }

        for (slot in 0 until ROWS) {
            assertEquals(netIdAt(slot), store.getNetId(slot, LinkReplicator.TARGET), "target at $slot")
            assertEquals(
                if (slot % 5 == 0) NetId.NONE else netIdAt(slot + 1),
                store.getNetId(slot, LinkReplicator.LAST_ATTACKER),
            )
            // The reference itself, never a copy: setObject's contract is retention.
            assertSame(squads[slot % squads.size], store.getObject(slot, LinkReplicator.SQUAD))
        }
    }

    @Test
    fun `growth preserves every column, not only the first`() {
        // The regression this pins: growing the store widens the column stride, so a plain
        // copyOf would leave field 1's values sitting where field 0's second half now lives.
        val store = ColumnarFieldStore(TestComponents.vitalsSchema, initialSlots = 4)
        for (slot in 0 until 4) {
            store.setFloat(slot, VitalsReplicator.HEALTH, slot.toFloat())
            store.setInt(slot, VitalsReplicator.SHIELD_CHARGES, 100 + slot)
            store.setLong(slot, VitalsReplicator.DAMAGE_DEALT, 900L + slot)
            store.setTick(slot, VitalsReplicator.RESPAWN_TICK, Tick(7L + slot))
        }

        store.ensureSlots(64)

        assertTrue(store.slotCount >= 64)
        for (slot in 0 until 4) {
            assertEquals(slot.toFloat(), store.getFloat(slot, VitalsReplicator.HEALTH))
            assertEquals(100 + slot, store.getInt(slot, VitalsReplicator.SHIELD_CHARGES))
            assertEquals(900L + slot, store.getLong(slot, VitalsReplicator.DAMAGE_DEALT))
            assertEquals(Tick(7L + slot), store.getTick(slot, VitalsReplicator.RESPAWN_TICK))
        }
    }

    @Test
    fun `reset reuses the backing arrays rather than allocating new ones`() {
        val store = ColumnarFieldStore(TestComponents.vitalsSchema, initialSlots = 32)
        val before = backingArrays(store)

        store.setFloat(0, VitalsReplicator.HEALTH, 1f)
        store.reset()
        store.setFloat(0, VitalsReplicator.HEALTH, 2f)

        val after = backingArrays(store)
        for (name in before.keys) {
            assertSame(before[name], after[name], "reset replaced the $name column")
        }
        assertEquals(2f, store.getFloat(0, VitalsReplicator.HEALTH))
    }

    @Test
    fun `reset drops object references so a pooled slot does not pin what it captured`() {
        val store = ColumnarFieldStore(TestComponents.linkSchema, initialSlots = 4)
        store.setObject(0, LinkReplicator.SQUAD, "squad.red")

        store.reset()

        assertEquals(null, store.getObject(0, LinkReplicator.SQUAD))
    }

    @Test
    fun `an accessor of the wrong kind fails instead of writing into the wrong column`() {
        val store = ColumnarFieldStore(TestComponents.linkSchema, initialSlots = 4)

        // `target` is a NetId. Written through setInt it would land in the very same backing
        // array, read back plausibly, and only surface as an entity referencing the wrong one.
        val failure = assertFailsWith<IllegalArgumentException> {
            store.setInt(0, LinkReplicator.TARGET, 5)
        }
        assertTrue(failure.message!!.contains("Link.target"), failure.message!!)
        assertTrue(failure.message!!.contains("NetId"), failure.message!!)
    }

    @Test
    fun `fieldEquals is bit-identical, so NaN equals itself and minus zero does not equal zero`() {
        val store = ColumnarFieldStore(TestComponents.vitalsSchema, initialSlots = 4)
        store.setFloat(0, VitalsReplicator.HEALTH, Float.NaN)
        store.setFloat(1, VitalsReplicator.HEALTH, Float.NaN)
        store.setFloat(2, VitalsReplicator.HEALTH, 0.0f)
        store.setFloat(3, VitalsReplicator.HEALTH, -0.0f)

        assertTrue(
            store.fieldEquals(0, 1, VitalsReplicator.HEALTH),
            "NaN must equal itself, or a delta encoder never converges",
        )
        assertTrue(
            !store.fieldEquals(2, 3, VitalsReplicator.HEALTH),
            "0.0f and -0.0f differ in the store, or a desync report never clears",
        )
    }

    @Test
    fun `the canonical comparison folds away exactly the differences the hash ignores`() {
        val store = ColumnarFieldStore(TestComponents.vitalsSchema, initialSlots = 4)
        store.setFloat(0, VitalsReplicator.HEALTH, 0.0f)
        store.setFloat(1, VitalsReplicator.HEALTH, -0.0f)
        store.setFloat(2, VitalsReplicator.HEALTH, Float.fromBits(0x7FC00001))
        store.setFloat(3, VitalsReplicator.HEALTH, Float.NaN)

        assertTrue(store.fieldEquals(0, store, 1, VitalsReplicator.HEALTH, FieldComparison.Canonical))
        assertTrue(store.fieldEquals(2, store, 3, VitalsReplicator.HEALTH, FieldComparison.Canonical))
        assertTrue(!store.fieldEquals(2, store, 3, VitalsReplicator.HEALTH, FieldComparison.Bitwise))
    }

    @Test
    fun `copySlot copies every column and seeds a baseline`() {
        val store = ColumnarFieldStore(TestComponents.linkSchema, initialSlots = 4)
        store.setNetId(0, LinkReplicator.TARGET, NetId.of(9, 2))
        store.setNetId(0, LinkReplicator.LAST_ATTACKER, NetId.of(4, 1))
        store.setObject(0, LinkReplicator.SQUAD, "squad.blue")

        store.copySlot(0, 3)

        assertEquals(NetId.of(9, 2), store.getNetId(3, LinkReplicator.TARGET))
        assertEquals(NetId.of(4, 1), store.getNetId(3, LinkReplicator.LAST_ATTACKER))
        assertSame("squad.blue", store.getObject(3, LinkReplicator.SQUAD))
        for (field in 0 until store.fieldCount) {
            assertTrue(store.fieldEquals(0, 3, field), "field $field was not copied")
        }
    }

    @Test
    fun `sizeBytes matches the summed backing-array footprint within five percent`() {
        val store = ColumnarFieldStore(TestComponents.vitalsSchema, initialSlots = 8)
        store.ensureSlots(ROWS)

        val arrays = backingArrays(store)
        val summed = 4L * (arrays.getValue("ints") as IntArray).size +
            8L * (arrays.getValue("longs") as LongArray).size +
            4L * (arrays.getValue("floats") as FloatArray).size +
            @Suppress("UNCHECKED_CAST")
            4L * (arrays.getValue("objects") as Array<Any?>).size

        val reported = store.sizeBytes()
        val drift = abs(reported - summed).toDouble() / summed.toDouble()
        assertTrue(
            drift <= 0.05,
            "sizeBytes reported $reported against a $summed-byte footprint, a ${drift * 100}% drift",
        )
    }

    @Test
    fun `a schema whose kinds do not line up with the replicator is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ComponentSchema.of(VitalsReplicator, "Vitals", listOf(FieldKind.Float, FieldKind.Int))
        }
        assertTrue(failure.message!!.contains("same index"), failure.message!!)
    }

    @Test
    fun `two independently built schemas lay their columns out identically`() {
        val first = ComponentSchema.of(VitalsReplicator, "Vitals", VitalsReplicator.kinds)
        val second = ComponentSchema.of(VitalsReplicator, "Vitals", VitalsReplicator.kinds)

        val a = ColumnarFieldStore(first, initialSlots = 4)
        val b = ColumnarFieldStore(second, initialSlots = 4)
        a.setLong(1, VitalsReplicator.DAMAGE_DEALT, 12345L)
        b.setLong(1, VitalsReplicator.DAMAGE_DEALT, 12345L)

        assertEquals(a.getLong(1, VitalsReplicator.DAMAGE_DEALT), b.getLong(1, VitalsReplicator.DAMAGE_DEALT))
        assertNotEquals(a.schema, b.schema, "two schema instances, to prove the layout is not shared state")
    }

    @Test
    fun `an Object field whose hashCode is an address is refused at the call site`() {
        // WorldHasher folds an Object column's hashCode into the determinism hash. An identity
        // hashCode there makes a bit-identical world hash differently in every process, and the
        // gate then reports a divergence that does not exist -- pointing at a field, as though
        // the simulation had diverged. Nothing else in the tree checks this obligation, so
        // without it a component declaring one field wrong silently poisons the gate.
        val store = ColumnarFieldStore(TestComponents.linkSchema, initialSlots = 4)

        val failure = assertFailsWith<IllegalArgumentException> {
            store.setObject(0, LinkReplicator.SQUAD, IdentityHashed())
        }

        assertTrue(failure.message!!.contains("identity hash"), failure.message!!)
        assertTrue(failure.message!!.contains("StableHash"), failure.message!!)
    }

    @Test
    fun `a String, a boxed primitive, a declared StableHash and null are all accepted`() {
        val store = ColumnarFieldStore(TestComponents.linkSchema, initialSlots = 4)

        store.setObject(0, LinkReplicator.SQUAD, "squad.red")
        assertEquals("squad.red", store.getObject(0, LinkReplicator.SQUAD))
        store.setObject(0, LinkReplicator.SQUAD, 42)
        assertEquals(42, store.getObject(0, LinkReplicator.SQUAD))
        val declared = ValueHashed("x")
        store.setObject(0, LinkReplicator.SQUAD, declared)
        assertEquals(declared, store.getObject(0, LinkReplicator.SQUAD))
        store.setObject(0, LinkReplicator.SQUAD, null)
        assertEquals(null, store.getObject(0, LinkReplicator.SQUAD))
    }

    /** An ordinary class: `hashCode` is `Object.hashCode`, which is an address. */
    private class IdentityHashed

    /** Opted in, and its `hashCode` really is a function of its value. */
    private data class ValueHashed(val name: String) : StableHash

    private companion object {

        /** Enough rows to force several regrows from an eight-slot store. */
        const val ROWS: Int = 10_000

        /** Spread over the awkward values on purpose: NaN, both zeroes, and the extremes. */
        fun healthAt(slot: Int): Float = when (slot % 6) {
            0 -> slot.toFloat() * 0.5f
            1 -> Float.NaN
            2 -> -0.0f
            3 -> Float.MAX_VALUE
            4 -> -Float.MAX_VALUE
            else -> -slot.toFloat()
        }

        fun intAt(slot: Int): Int = when (slot % 4) {
            0 -> slot
            1 -> -slot
            2 -> Int.MIN_VALUE + slot
            else -> Int.MAX_VALUE - slot
        }

        fun longAt(slot: Int): Long = when (slot % 4) {
            0 -> slot.toLong()
            1 -> -slot.toLong()
            2 -> Long.MIN_VALUE + slot
            else -> Long.MAX_VALUE - slot
        }

        fun netIdAt(slot: Int): NetId =
            NetId.of(slot % NetId.MAX_INDICES, slot % NetId.GENERATION_MODULUS)

        /**
         * The store's four backing arrays, by field name.
         *
         * Read reflectively because the pooling claim is about object *identity* and there is
         * no honest way to observe that from outside — and inventing a production accessor
         * only tests can use would be worse than a four-line reflective read here.
         */
        fun backingArrays(store: ColumnarFieldStore): Map<String, Any> =
            listOf("ints", "longs", "floats", "objects").associateWith { name ->
                ColumnarFieldStore::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }
                    .get(store)
            }
    }
}
