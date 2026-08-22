package dev.wildware.udea.codegen

import dev.wildware.udea.codegen.fixtures.Combat
import dev.wildware.udea.codegen.fixtures.CombatReplicator
import dev.wildware.udea.codegen.fixtures.Placement
import dev.wildware.udea.codegen.fixtures.PlacementReplicator
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.ArrayFieldStore
import dev.wildware.udea.core.fixtures.Vec2
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The field types beyond the primitives: a lowered composite, a `NetId` and a `Tick`.
 *
 * These are the three the frozen contract names and the generator could not previously emit at
 * all — a `Vector2` position, the single most common replicated field in a MOBA, had no path
 * through the generator except the old one's `putSerializable` fallback.
 *
 * The executable specifications are `udea-core`'s hand-written `TransformReplicator` (for the
 * lowering and the `Tick`) and `TargetingReplicator` (for the `NetId`). Everything asserted
 * here is asserted there against hand-written code, which is what makes them a specification
 * rather than a description of whatever the generator happens to do.
 */
class GeneratedReplicatorFieldTypeTest {

    private val datagram = ByteArray(256)

    // --- composite lowering --------------------------------------------------------------

    @Test
    fun `a composite property becomes one field per component, with a dotted name`() {
        // Three annotated properties, four fields. The contract's own worked example.
        assertContentEquals(
            listOf("position.x", "position.y", "rotation", "settledAt"),
            PlacementReplicator.fieldNames,
        )
        assertEquals(4, PlacementReplicator.FIELD_COUNT)
        assertEquals(0, PlacementReplicator.FIELD_POSITION_X)
        assertEquals(1, PlacementReplicator.FIELD_POSITION_Y)
        assertEquals(2, PlacementReplicator.FIELD_ROTATION)
        assertEquals(3, PlacementReplicator.FIELD_SETTLED_AT)
    }

    @Test
    fun `each lowered component gets its own mask bit, so a diff names the axis that moved`() {
        // The reason lowering exists rather than one opaque field per vector: `desync_report`
        // indexes `fieldNames` with each set bit, so one bit has to mean one comparable value.
        val store = ArrayFieldStore(2, PlacementReplicator.FIELD_COUNT)
        PlacementReplicator.capture(Placement(position = Vec2(1f, 2f)), store, 0)
        PlacementReplicator.capture(Placement(position = Vec2(1f, 9f)), store, 1)

        val changed = PlacementReplicator.diff(store, 0, 1)

        assertEquals(MaskOps.single(PlacementReplicator.FIELD_POSITION_Y), changed)
        assertEquals(
            listOf("position.y"),
            buildList { MaskOps.forEachSetBit(changed) { add(PlacementReplicator.fieldNames[it]) } },
        )
    }

    @Test
    fun `apply restores a composite in place, keeping the identity rendering holds`() {
        // `apply` mutates the caller's component in place (spec 3.1). For a composite that
        // means writing through the property: replacing the vector would break every reference
        // physics and rendering already hold, which is the one idea kept from the old
        // `InPlaceSerializer`.
        val store = ArrayFieldStore(1, PlacementReplicator.FIELD_COUNT)
        PlacementReplicator.capture(Placement(position = Vec2(4f, 5f)), store, 0)

        val target = Placement()
        val vector = target.position
        PlacementReplicator.apply(store, 0, target, PlacementReplicator.allMask)

        assertSame(vector, target.position, "apply must not replace the vector")
        assertEquals(4f, target.position.x)
        assertEquals(5f, target.position.y)
    }

    @Test
    fun `a lowered component is reachable through the agent's field access by its dotted name`() {
        val placement = Placement(position = Vec2(7f, 8f))

        assertEquals(7f, PlacementReplicator.getField(placement, PlacementReplicator.FIELD_POSITION_X))
        PlacementReplicator.setField(placement, PlacementReplicator.FIELD_POSITION_Y, 11f)

        assertEquals(11f, placement.position.y)
        assertEquals(8f, PlacementReplicator.getField(Placement(position = Vec2(7f, 8f)), 1))
    }

    // --- Tick and NetId are field types, not Long and Int in disguise ---------------------

    @Test
    fun `a Tick field round-trips as a Tick`() {
        val source = Placement(settledAt = Tick(1_234_567_890_123L))
        val store = ArrayFieldStore(1, PlacementReplicator.FIELD_COUNT)
        PlacementReplicator.capture(source, store, 0)

        // Through the store's own Tick accessor, not getLong: the value is typed all the way.
        assertEquals(Tick(1_234_567_890_123L), store.getTick(0, PlacementReplicator.FIELD_SETTLED_AT))

        val restored = Placement()
        PlacementReplicator.apply(store, 0, restored, PlacementReplicator.allMask)
        assertEquals(source.settledAt, restored.settledAt)
        assertEquals(
            Tick(1_234_567_890_123L),
            PlacementReplicator.getField(restored, PlacementReplicator.FIELD_SETTLED_AT),
        )
    }

    @Test
    fun `a NetId field round-trips through real bytes, generation included`() {
        val source = Combat(target = NetId.of(1234, 7), lastAttacker = NetId.of(9, 255))
        val store = ArrayFieldStore(1, CombatReplicator.FIELD_COUNT)
        CombatReplicator.capture(source, store, 0)

        val writer = BitBufferWriter(datagram)
        CombatReplicator.write(store, 0, CombatReplicator.allMask, writer)

        val received = ArrayFieldStore(1, CombatReplicator.FIELD_COUNT)
        val mask = CombatReplicator.read(BitBufferReader(datagram, 0, writer.byteLength), received, 0)
        val restored = Combat()
        CombatReplicator.apply(received, 0, restored, mask)

        assertEquals(NetId.of(1234, 7), restored.target)
        assertEquals(NetId.of(9, 255), restored.lastAttacker)
    }

    @Test
    fun `a stale generation is a change, so a retarget is replicated`() {
        // Two ids at the same index differing only in generation are different entities. A
        // generator that stored a NetId through setInt and dropped the generation would make a
        // retarget onto a recycled slot look like no change at all.
        val store = ArrayFieldStore(2, CombatReplicator.FIELD_COUNT)
        CombatReplicator.capture(Combat(target = NetId.of(5, 0)), store, 0)
        CombatReplicator.capture(Combat(target = NetId.of(5, 1)), store, 1)

        assertEquals(
            MaskOps.single(CombatReplicator.FIELD_TARGET),
            CombatReplicator.diff(store, 0, 1),
        )
    }

    @Test
    fun `read rejects a NetId word with reserved bits set, before the store sees it`() {
        // `read` is the trust boundary. `NetId.ofRaw` is the range check for an entity
        // reference, and it has to run there rather than in `apply`, or the poisoned word is
        // already in every snapshot slot captured from it.
        val writer = BitBufferWriter(datagram)
        MaskOps.writeTo(
            MaskOps.single(CombatReplicator.FIELD_TARGET),
            writer,
            CombatReplicator.FIELD_COUNT,
        )
        writer.writeInt(0x0F00_0001)

        val store = ArrayFieldStore(1, CombatReplicator.FIELD_COUNT)
        store.setNetId(0, CombatReplicator.FIELD_TARGET, NetId.of(3, 0))

        assertFailsWith<IllegalArgumentException> {
            CombatReplicator.read(BitBufferReader(datagram, 0, writer.byteLength), store, 0)
        }
        assertEquals(
            NetId.of(3, 0),
            store.getNetId(0, CombatReplicator.FIELD_TARGET),
            "the rejected word must not have reached the FieldStore",
        )
    }

    @Test
    fun `NONE round-trips like any other id`() {
        val store = ArrayFieldStore(1, CombatReplicator.FIELD_COUNT)
        CombatReplicator.capture(Combat(target = NetId.NONE), store, 0)

        val writer = BitBufferWriter(datagram)
        CombatReplicator.write(store, 0, CombatReplicator.allMask, writer)
        val received = ArrayFieldStore(1, CombatReplicator.FIELD_COUNT)
        val mask = CombatReplicator.read(BitBufferReader(datagram, 0, writer.byteLength), received, 0)

        val restored = Combat(target = NetId.of(3, 0))
        CombatReplicator.apply(received, 0, restored, mask)
        assertEquals(NetId.NONE, restored.target)
    }

    // --- the generated source uses the typed accessors -------------------------------------

    @Test
    fun `a NetId and a Tick go through their own store accessors, not setInt and setLong`() {
        // Spec 5 makes NetId a primitive field type. If the generator wrote it through setInt
        // the round trip above would still pass — and `FieldStore.fieldEquals`, which
        // `desync_report` walks without knowing Kotlin types, would compare a different
        // representation from the one `diff` compares.
        val combat = GeneratedSources.files.single { it.name == "CombatReplicator.kt" }.readText()
        val placement = GeneratedSources.files.single { it.name == "PlacementReplicator.kt" }.readText()

        assertTrue("store.setNetId(" in combat && "store.getNetId(" in combat, combat)
        assertTrue("store.setTick(" in placement && "store.getTick(" in placement, placement)
        assertTrue("NetId.ofRaw(" in combat, "read must rebuild a NetId through its factory:\n$combat")
    }
}
