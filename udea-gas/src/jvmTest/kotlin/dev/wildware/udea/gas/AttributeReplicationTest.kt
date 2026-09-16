package dev.wildware.udea.gas

import dev.wildware.udea.core.fixtures.ArrayBitWriter
import dev.wildware.udea.core.fixtures.ArrayFieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.StableHash
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Forty attributes replicate through one field, and changing one puts one element on the wire.
 *
 * `FieldMask` is 64 bits per component, and the old shape spent one bit per attribute
 * (`example/.../CharacterAttributeSet.kt:20-52`, one `@UdeaSync` property each). Forty of those on
 * a champion is two thirds of the budget for one component. Spec 7 names the dense indexed array as
 * the mitigation; this measures it.
 */
class AttributeReplicationTest {

    private fun wideTable(count: Int = 40): AttributeTable = AttributeTableBuilder().apply {
        repeat(count) { index ->
            // Zero-padded so ascending name order is ascending index order, which makes the
            // assertions below readable rather than making them depend on lexicographic surprise.
            add(AttributeDecl("game.Champion.attr%02d".format(index), defaultBase = index.toFloat()), "game")
        }
    }.build()

    @Test
    fun `a forty attribute set replicates through exactly one net field`() {
        val table = wideTable()
        val replicator = AttributesReplicator(table)

        assertEquals(40, table.count)
        assertEquals(1, replicator.fieldNames.size, "one lowered field, not forty")
        assertEquals(1, MaskOps.cardinality(replicator.netMask))
        assertTrue(
            MaskOps.cardinality(replicator.allMask) <= 8,
            "the component must report at most eight replicated fields, and it reports " +
                MaskOps.cardinality(replicator.allMask),
        )
    }

    @Test
    fun `the schema the snapshot ring builds accepts the single object column`() {
        val table = wideTable()
        val replicator = AttributesReplicator(table)
        val schema = ComponentSchema.of(replicator, "Attributes", listOf(FieldKind.Object))

        assertEquals(1, schema.fieldCount)
        assertEquals("base", schema.nameOf(0))

        // `ColumnarFieldStore.setObject` refuses a value whose hashCode is an identity hash,
        // because `WorldHasher` folds an Object column's hash into the determinism hash. Declaring
        // StableHash is only half of that promise; the other half is that the hash is actually a
        // function of the value, which is what two independently built equal vectors prove.
        val values = floatArrayOf(1f, -0f, Float.NaN)
        val one: StableHash = AttributeVector.of(values)
        val two: StableHash = AttributeVector.of(values.copyOf())
        assertEquals(one.hashCode(), two.hashCode(), "the hash must be a function of the values")
        assertEquals(one, two, "and so must equality")
    }

    @Test
    fun `capture then apply round-trips every value`() {
        val table = wideTable()
        val replicator = AttributesReplicator(table)
        val store = ArrayFieldStore(slotCount = 2, fieldCount = AttributesReplicator.FIELD_COUNT)

        val source = Attributes(table)
        repeat(table.count) { source.setBase(AttributeId(it), it * 1.5f) }
        replicator.capture(source, store, slot = 0)

        val target = Attributes(table)
        replicator.apply(store, 0, target, replicator.allMask)

        assertContentEquals(source.base, target.base)
    }

    @Test
    fun `an unchanged component diffs to an empty mask`() {
        val table = wideTable()
        val replicator = AttributesReplicator(table)
        val store = ArrayFieldStore(slotCount = 2, fieldCount = AttributesReplicator.FIELD_COUNT)
        val component = Attributes(table)

        replicator.capture(component, store, 0)
        replicator.capture(component, store, 1)

        assertTrue(MaskOps.isEmpty(replicator.diff(store, 0, 1)))
    }

    @Test
    fun `changing one attribute puts only that element on the wire`() {
        val table = wideTable()
        val replicator = AttributesReplicator(table)
        val store = ArrayFieldStore(slotCount = 2, fieldCount = AttributesReplicator.FIELD_COUNT)
        val component = Attributes(table)

        replicator.capture(component, store, 0)
        component.setBase(table.idOf("game.Champion.attr07"), 99f)
        replicator.capture(component, store, 1)

        assertTrue(MaskOps.isNotEmpty(replicator.diff(store, 0, 1)), "the component did change")

        val writer = ArrayBitWriter()
        val changed = replicator.writeDelta(store, baselineSlot = 0, slot = 1, out = writer)

        assertEquals(1, changed, "one attribute changed, so one element goes on the wire")
        // One 64-bit mask word for 40 attributes, plus one 32-bit float.
        assertEquals(96L, writer.bitPosition, "a one-element delta is a mask word and one float")

        val target = component.base.copyOf().also { it[table.idOf("game.Champion.attr07").index] = 0f }
        assertEquals(1, replicator.readDelta(writer.toReader(), target))
        assertContentEquals(component.base, target)
    }

    @Test
    fun `an unchanged component writes no floats at all`() {
        val table = wideTable()
        val replicator = AttributesReplicator(table)
        val store = ArrayFieldStore(slotCount = 2, fieldCount = AttributesReplicator.FIELD_COUNT)
        val component = Attributes(table)

        replicator.capture(component, store, 0)
        replicator.capture(component, store, 1)

        val writer = ArrayBitWriter()
        assertEquals(0, replicator.writeDelta(store, 0, 1, writer))
        assertEquals(64L, writer.bitPosition, "one empty mask word and nothing else")
    }

    @Test
    fun `current is derived and is never captured`() {
        val table = wideTable()
        val replicator = AttributesReplicator(table)
        val store = ArrayFieldStore(slotCount = 2, fieldCount = AttributesReplicator.FIELD_COUNT)
        val component = Attributes(table)

        replicator.capture(component, store, 0)
        // A modifier writes `current` only; the captured state must not notice.
        component.current[3] = 1234f
        replicator.capture(component, store, 1)

        assertTrue(
            MaskOps.isEmpty(replicator.diff(store, 0, 1)),
            "current is a pure function of base and the effect list, so capturing it would let a " +
                "snapshot disagree with a restore",
        )
    }

    @Test
    fun `a float that differs only in sign of zero is still a change`() {
        val table = wideTable(2)
        val replicator = AttributesReplicator(table)
        val store = ArrayFieldStore(slotCount = 2, fieldCount = AttributesReplicator.FIELD_COUNT)
        val component = Attributes(table)
        component.setBase(AttributeId(0), 0f)

        replicator.capture(component, store, 0)
        component.setBase(AttributeId(0), -0f)
        replicator.capture(component, store, 1)

        assertTrue(
            MaskOps.isNotEmpty(replicator.diff(store, 0, 1)),
            "raw-bit comparison, matching FieldStore.fieldEquals: -0.0 is a different word",
        )
    }
}
