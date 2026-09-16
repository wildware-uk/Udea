package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `diffInto` over hand-built stores: the primitive behind delta write and `desync_report`.
 *
 * Every case here is a shape the report has to be able to name — a changed value, a component
 * that appeared, an entity that appeared — because spec 3.1 says a desync report is a
 * field-by-field comparison that names the differing field, not a byte diff that says
 * "somewhere in here".
 */
class WorldFieldStoreDiffTest {

    private val registry = TestComponents.registry()
    private val movement = registry.indexOf(MovementReplicator.typeId)
    private val vitals = registry.indexOf(VitalsReplicator.typeId)
    private val diff = FieldDiff()

    @Test
    fun `two identical stores produce an empty diff`() {
        val left = store(listOf(NetId.of(0, 0), NetId.of(3, 1)))
        val right = store(listOf(NetId.of(0, 0), NetId.of(3, 1)))

        assertTrue(left.diffInto(right, diff), "identical stores must agree")
        assertEquals(0, diff.size, "empty diff expected, got ${entries()}")
        assertTrue(diff.isEmpty)
    }

    @Test
    fun `exactly the changed fields are reported, and nothing else`() {
        val ids = listOf(NetId.of(0, 0), NetId.of(3, 1), NetId.of(7, 0))
        val left = store(ids)
        val right = store(ids)

        right.storeAt(movement).setFloat(1, MovementReplicator.POSITION_Y, 99f)
        right.storeAt(vitals).setInt(2, VitalsReplicator.SHIELD_CHARGES, 42)

        assertTrue(!left.diffInto(right, diff))
        assertEquals(
            listOf(
                Triple(NetId.of(3, 1), MovementReplicator.typeId, MovementReplicator.POSITION_Y),
                Triple(NetId.of(7, 0), VitalsReplicator.typeId, VitalsReplicator.SHIELD_CHARGES),
            ),
            entries(),
        )
    }

    @Test
    fun `a float that differs only in sign of zero is reported bitwise and folded canonically`() {
        val ids = listOf(NetId.of(0, 0))
        val left = store(ids)
        val right = store(ids)
        left.storeAt(movement).setFloat(0, MovementReplicator.POSITION_X, 0.0f)
        right.storeAt(movement).setFloat(0, MovementReplicator.POSITION_X, -0.0f)

        left.diffInto(right, diff, FieldComparison.Bitwise)
        assertEquals(1, diff.size, "bitwise comparison must see the sign bit a write would change")

        left.diffInto(right, diff, FieldComparison.Canonical)
        assertEquals(0, diff.size, "the determinism gate must fold a benign sign of zero away")
    }

    @Test
    fun `a component present on one side only is reported as a presence difference`() {
        val ids = listOf(NetId.of(0, 0), NetId.of(1, 0))
        val left = store(ids)
        val right = store(ids, componentsFor = { row -> if (row == 1) listOf(movement) else null })

        assertTrue(!left.diffInto(right, diff))
        val presence = (0 until diff.size).filter { diff.isPresenceAt(it) }
        assertEquals(
            setOf(VitalsReplicator.typeId, LinkReplicator.typeId),
            presence.map { diff.typeIdAt(it) }.toSet(),
            "the two components the right-hand entity lacks must be named",
        )
        assertTrue(presence.all { diff.netIdAt(it) == NetId.of(1, 0) })
    }

    @Test
    fun `an entity present on one side only has every one of its components reported`() {
        val left = store(listOf(NetId.of(0, 0), NetId.of(5, 0)))
        val right = store(listOf(NetId.of(0, 0)))

        assertTrue(!left.diffInto(right, diff))
        assertEquals(registry.size, diff.size, "one presence entry per component, got ${entries()}")
        assertTrue((0 until diff.size).all { diff.netIdAt(it) == NetId.of(5, 0) })
        assertTrue((0 until diff.size).all { diff.isPresenceAt(it) })
    }

    @Test
    fun `an entity whose generation changed is a different entity, not a changed one`() {
        // The whole point of the generation counter: index 4 recycled is not index 4 rewound.
        val left = store(listOf(NetId.of(4, 0)))
        val right = store(listOf(NetId.of(4, 1)))

        assertTrue(!left.diffInto(right, diff))
        assertEquals(registry.size * 2, diff.size, "both entities are one-sided, got ${entries()}")
        assertTrue((0 until diff.size).all { diff.isPresenceAt(it) })
    }

    @Test
    fun `rows must be appended in ascending NetId order`() {
        val fields = WorldFieldStore(registry, initialRows = 4)
        fields.appendRow(NetId.of(5, 0))

        val failure = assertFailsWith<IllegalArgumentException> { fields.appendRow(NetId.of(2, 0)) }
        assertTrue(failure.message!!.contains("ascending"), failure.message!!)
    }

    @Test
    fun `rowOf finds every row it holds and refuses the ones it does not`() {
        val ids = List(64) { NetId.of(it * 3, it % 4) }
        val fields = store(ids)

        for (index in ids.indices) assertEquals(index, fields.rowOf(ids[index]))
        assertEquals(WorldFieldStore.NO_ROW, fields.rowOf(NetId.of(1, 0)))
        assertEquals(WorldFieldStore.NO_ROW, fields.rowOf(NetId.NONE))
    }

    @Test
    fun `the registry orders component types by id whatever order they were registered in`() {
        val ids = registry.let { reg -> (0 until reg.size).map { reg.schemaAt(it).typeId.raw } }
        assertEquals(ids.sorted(), ids, "canonical order is ascending ComponentTypeId")
        assertEquals(
            listOf("Movement", "Vitals", "Link"),
            (0 until registry.size).map { registry.schemaAt(it).typeName },
        )
    }

    /** A store with one row per id, every component present unless [componentsFor] says otherwise. */
    private fun store(
        ids: List<NetId>,
        componentsFor: (Int) -> List<Int>? = { null },
    ): WorldFieldStore {
        val fields = WorldFieldStore(registry, initialRows = maxOf(1, ids.size))
        for (index in ids.indices) {
            val row = fields.appendRow(ids[index])
            val components = componentsFor(index) ?: (0 until registry.size).toList()
            for (component in components) {
                val slot = fields.claimSlot(row, component)
                val store = fields.storeAt(component)
                for (field in 0 until store.fieldCount) {
                    when (store.schema.kindOf(field)) {
                        FieldKind.Bool -> store.setBoolean(slot, field, index and 1 == 0)
                        FieldKind.Int -> store.setInt(slot, field, index)
                        FieldKind.Long -> store.setLong(slot, field, index.toLong())
                        FieldKind.Float -> store.setFloat(slot, field, index.toFloat())
                        FieldKind.NetId -> store.setNetId(slot, field, NetId.of(index, 0))
                        FieldKind.Tick -> store.setTick(slot, field, dev.wildware.udea.core.Tick(index.toLong()))
                        FieldKind.Object -> store.setObject(slot, field, SnapshotWorld.SQUAD_RED)
                    }
                }
            }
        }
        return fields
    }

    private fun entries() =
        (0 until diff.size).map { Triple(diff.netIdAt(it), diff.typeIdAt(it), diff.fieldAt(it)) }
}
