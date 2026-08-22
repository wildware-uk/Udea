package dev.wildware.udea.core.replication

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.ArrayBitWriter
import dev.wildware.udea.core.fixtures.ArrayFieldStore
import dev.wildware.udea.core.fixtures.Transform
import dev.wildware.udea.core.fixtures.TransformReplicator
import dev.wildware.udea.core.fixtures.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives the hand-written `TransformReplicator` through the whole contract.
 *
 * Everything `udea-codegen` emits has to behave exactly like this, which is why the
 * specification is committed rather than generated.
 */
class ReplicatorContractTest {

    private val replicator = TransformReplicator

    private fun store() = ArrayFieldStore(slotCount = 4, fieldCount = replicator.fieldNames.size)

    private fun transform(x: Float, y: Float, rotation: Float, grounded: Long) =
        Transform(Vec2(x, y), rotation, Tick(grounded))

    @Test
    fun `capture, diff, write, read and apply preserve every field`() {
        val source = transform(x = 12.5f, y = -3.25f, rotation = 1.75f, grounded = 42L)
        val store = store()

        replicator.capture(source, store, SLOT_CAPTURED)

        // Everything changed relative to an empty baseline.
        val mask = replicator.diff(store, SLOT_CAPTURED, SLOT_BASELINE)
        assertEquals(replicator.allMask, mask)

        val writer = ArrayBitWriter()
        replicator.write(store, SLOT_CAPTURED, mask, writer)

        val readMask = replicator.read(writer.toReader(), store, SLOT_RECEIVED)
        assertEquals(mask, readMask)

        val destination = Transform()
        replicator.apply(store, SLOT_RECEIVED, destination, readMask)

        assertEquals(12.5f, destination.position.x)
        assertEquals(-3.25f, destination.position.y)
        assertEquals(1.75f, destination.rotation)
        assertEquals(Tick(42), destination.lastGroundedTick)
    }

    @Test
    fun `a Sim field is snapshotted but never written to the wire`() {
        // Jungle timers and bot blackboards must rewind and must never reach a client.
        val source = transform(x = 1f, y = 2f, rotation = 3f, grounded = 99L)
        val store = store()
        replicator.capture(source, store, SLOT_CAPTURED)

        val netDelta = MaskOps.and(
            replicator.diff(store, SLOT_CAPTURED, SLOT_BASELINE),
            replicator.netMask,
        )
        assertFalse(
            MaskOps.test(netDelta, TransformReplicator.FIELD_LAST_GROUNDED_TICK),
            "lastGroundedTick is @Sim, so a delta write must not carry it",
        )

        val writer = ArrayBitWriter()
        replicator.write(store, SLOT_CAPTURED, netDelta, writer)
        replicator.read(writer.toReader(), store, SLOT_RECEIVED)

        val destination = transform(x = 0f, y = 0f, rotation = 0f, grounded = 7L)
        replicator.apply(store, SLOT_RECEIVED, destination, netDelta)

        assertEquals(1f, destination.position.x)
        assertEquals(3f, destination.rotation)
        assertEquals(
            Tick(7),
            destination.lastGroundedTick,
            "the receiver's @Sim field must be untouched by a net delta",
        )
    }

    @Test
    fun `capture uses allMask so a snapshot carries the Sim field`() {
        val source = transform(x = 1f, y = 2f, rotation = 3f, grounded = 99L)
        val store = store()
        replicator.capture(source, store, SLOT_CAPTURED)

        val restored = Transform()
        replicator.apply(store, SLOT_CAPTURED, restored, replicator.allMask)

        assertEquals(Tick(99), restored.lastGroundedTick)
    }

    @Test
    fun `netMask is a subset of allMask and neither is empty`() {
        assertTrue(MaskOps.containsAll(replicator.allMask, replicator.netMask))
        assertTrue(MaskOps.isNotEmpty(replicator.netMask))
        assertEquals(
            replicator.fieldNames.size,
            MaskOps.cardinality(replicator.allMask),
            "allMask must select exactly the declared fields",
        )
        assertEquals(3, MaskOps.cardinality(replicator.netMask))
    }

    @Test
    fun `fieldNames is index-aligned with mask bit positions`() {
        // desync_report(tick) names the differing field by indexing fieldNames with the set
        // bits of a difference mask, so the alignment is load-bearing.
        replicator.fieldNames.forEachIndexed { index, name ->
            assertTrue(
                MaskOps.test(replicator.allMask, index),
                "allMask must have a bit for $name at index $index",
            )
        }
        assertFalse(MaskOps.test(replicator.allMask, replicator.fieldNames.size))

        assertEquals(
            listOf("position.x", "position.y", "rotation"),
            buildList {
                MaskOps.forEachSetBit(replicator.netMask) { add(replicator.fieldNames[it]) }
            },
        )
    }

    @Test
    fun `diff of two identical slots is the empty mask`() {
        val transform = transform(x = 4f, y = 5f, rotation = 6f, grounded = 7L)
        val store = store()
        replicator.capture(transform, store, SLOT_CAPTURED)
        replicator.capture(transform, store, SLOT_RECEIVED)

        assertEquals(MaskOps.EMPTY, replicator.diff(store, SLOT_CAPTURED, SLOT_RECEIVED))
    }

    @Test
    fun `write of an empty mask emits zero bits`() {
        val store = store()
        replicator.capture(transform(1f, 2f, 3f, 4L), store, SLOT_CAPTURED)

        val writer = ArrayBitWriter()
        replicator.write(store, SLOT_CAPTURED, MaskOps.EMPTY, writer)

        assertEquals(
            0L,
            writer.bitPosition,
            "an unchanged component must cost nothing, not even a mask",
        )
    }

    @Test
    fun `a delta carries only the changed fields`() {
        val store = store()
        val transform = transform(x = 1f, y = 2f, rotation = 3f, grounded = 10L)
        replicator.capture(transform, store, SLOT_BASELINE)
        store.copySlot(SLOT_BASELINE, SLOT_RECEIVED)

        transform.rotation = 3.5f
        replicator.capture(transform, store, SLOT_CAPTURED)

        val delta = replicator.diff(store, SLOT_CAPTURED, SLOT_BASELINE)
        assertEquals(MaskOps.of(TransformReplicator.FIELD_ROTATION), delta)

        val writer = ArrayBitWriter()
        replicator.write(store, SLOT_CAPTURED, delta, writer)
        // 4 mask bits plus one 32-bit float.
        assertEquals(36L, writer.bitPosition)

        assertEquals(delta, replicator.read(writer.toReader(), store, SLOT_RECEIVED))
        assertEquals(3.5f, store.getFloat(SLOT_RECEIVED, TransformReplicator.FIELD_ROTATION))
        assertEquals(1f, store.getFloat(SLOT_RECEIVED, TransformReplicator.FIELD_POSITION_X))
        assertEquals(Tick(10), store.getTick(SLOT_RECEIVED, TransformReplicator.FIELD_LAST_GROUNDED_TICK))
    }

    @Test
    fun `capture-and-diff notices an in-place vector mutation`() {
        // The reason replication is capture-and-diff and not setter instrumentation: no
        // setter fires for `position.set(...)`, so dirty-on-assign would under-replicate
        // the field that matters most.
        val store = store()
        val transform = transform(x = 0f, y = 0f, rotation = 0f, grounded = 0L)
        replicator.capture(transform, store, SLOT_BASELINE)

        transform.position.set(10f, 20f)
        replicator.capture(transform, store, SLOT_CAPTURED)

        assertEquals(
            MaskOps.of(TransformReplicator.FIELD_POSITION_X, TransformReplicator.FIELD_POSITION_Y),
            replicator.diff(store, SLOT_CAPTURED, SLOT_BASELINE),
        )
    }

    @Test
    fun `apply mutates in place and keeps the vector identity`() {
        // Restoring 1000 entities must not allocate 1000 components, and anything holding a
        // reference to the vector must see the restored value.
        val store = store()
        replicator.capture(transform(9f, 8f, 7f, 6L), store, SLOT_CAPTURED)

        val destination = Transform()
        val heldVector = destination.position
        replicator.apply(store, SLOT_CAPTURED, destination, replicator.allMask)

        assertSame(heldVector, destination.position)
        assertEquals(9f, heldVector.x)
        assertEquals(8f, heldVector.y)
    }

    @Test
    fun `getField and setField round-trip for every index in fieldNames`() {
        val transform = transform(x = 1f, y = 2f, rotation = 3f, grounded = 4L)

        val values: List<Any> = listOf(11f, 12f, 13f, Tick(14))
        replicator.fieldNames.indices.forEach { index ->
            replicator.setField(transform, index, values[index])
            assertEquals(
                values[index],
                replicator.getField(transform, index),
                "field ${replicator.fieldNames[index]} did not round-trip",
            )
        }

        assertEquals(11f, transform.position.x)
        assertEquals(12f, transform.position.y)
        assertEquals(13f, transform.rotation)
        assertEquals(Tick(14), transform.lastGroundedTick)
    }

    @Test
    fun `an out of range field index throws a typed error`() {
        val transform = Transform()
        val fieldCount = replicator.fieldNames.size

        for (index in intArrayOf(-1, fieldCount, fieldCount + 100)) {
            val onGet = assertFailsWith<NoSuchFieldIndexException> {
                replicator.getField(transform, index)
            }
            assertEquals(index, onGet.fieldIndex)
            assertEquals(fieldCount, onGet.fieldCount)
            assertEquals("Transform", onGet.typeName)
            assertTrue("Transform" in onGet.message.orEmpty(), onGet.message.orEmpty())

            assertFailsWith<NoSuchFieldIndexException> {
                replicator.setField(transform, index, 0f)
            }
        }
    }

    @Test
    fun `setField rejects a value of the wrong type`() {
        val transform = Transform()

        assertFailsWith<IllegalArgumentException> {
            replicator.setField(transform, TransformReplicator.FIELD_ROTATION, "not a float")
        }
        assertFailsWith<IllegalArgumentException> {
            replicator.setField(transform, TransformReplicator.FIELD_LAST_GROUNDED_TICK, 3)
        }
    }

    @Test
    fun `the field store compares fields without knowing their types`() {
        // desync_report walks the store, not the component, so fieldEquals has to agree with
        // the generated typed diff.
        val store = store()
        val transform = transform(x = 1f, y = 2f, rotation = 3f, grounded = 4L)
        replicator.capture(transform, store, SLOT_BASELINE)
        transform.rotation = 30f
        replicator.capture(transform, store, SLOT_CAPTURED)

        val typed = replicator.diff(store, SLOT_CAPTURED, SLOT_BASELINE)
        val untyped = buildList {
            for (field in replicator.fieldNames.indices) {
                if (!store.fieldEquals(SLOT_CAPTURED, SLOT_BASELINE, field)) add(field)
            }
        }

        assertEquals(untyped, buildList { MaskOps.forEachSetBit(typed) { add(it) } })
    }

    @Test
    fun `typeId is stable`() {
        assertEquals(1, replicator.typeId)
    }

    private companion object {
        const val SLOT_BASELINE: Int = 0
        const val SLOT_CAPTURED: Int = 1
        const val SLOT_RECEIVED: Int = 2
    }
}
