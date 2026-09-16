package dev.wildware.udea.core.fixtures

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.FieldStore

/**
 * A dead-simple [FieldStore] for tests.
 *
 * The real store — pooled buffers, ring cadences, `diffInto`, a 64MB budget — belongs to the
 * snapshot epic. This exists so a codegen or contract test can exercise a `Replicator`
 * without dragging a ring buffer in, and so there is an obviously-correct reference to check
 * the fast implementation against.
 *
 * Every primitive is widened into one `Long` column, which is why [fieldEquals] can compare
 * two slots without knowing the component's types. Floats are stored as raw bits, so
 * `fieldEquals` distinguishes `-0.0` from `0.0` and treats `NaN` as equal to itself — both of
 * which are what a delta encoder wants, because they are what actually changed on the wire,
 * and both of which every `Replicator.diff` must match (see `FieldStore.fieldEquals`).
 *
 * Reference-typed fields go in a parallel object column, which stores the reference exactly
 * as given: [setObject]'s deep-immutability requirement is a caller obligation and this class
 * does not enforce it, because it cannot.
 */
public class ArrayFieldStore(
    override val slotCount: Int,
    override val fieldCount: Int,
) : FieldStore {

    init {
        require(slotCount > 0) { "slotCount must be positive, was $slotCount" }
        require(fieldCount > 0) { "fieldCount must be positive, was $fieldCount" }
    }

    private val primitives = LongArray(slotCount * fieldCount)
    private val objects = arrayOfNulls<Any?>(slotCount * fieldCount)

    override fun setBoolean(slot: Int, field: Int, value: Boolean) {
        primitives[cell(slot, field)] = if (value) 1L else 0L
    }

    override fun getBoolean(slot: Int, field: Int): Boolean = primitives[cell(slot, field)] != 0L

    override fun setInt(slot: Int, field: Int, value: Int) {
        primitives[cell(slot, field)] = value.toLong()
    }

    override fun getInt(slot: Int, field: Int): Int = primitives[cell(slot, field)].toInt()

    override fun setLong(slot: Int, field: Int, value: Long) {
        primitives[cell(slot, field)] = value
    }

    override fun getLong(slot: Int, field: Int): Long = primitives[cell(slot, field)]

    override fun setFloat(slot: Int, field: Int, value: Float) {
        primitives[cell(slot, field)] = value.toRawBits().toLong()
    }

    override fun getFloat(slot: Int, field: Int): Float =
        Float.fromBits(primitives[cell(slot, field)].toInt())

    override fun setNetId(slot: Int, field: Int, value: NetId) {
        primitives[cell(slot, field)] = value.raw.toLong()
    }

    override fun getNetId(slot: Int, field: Int): NetId =
        NetId.ofRaw(primitives[cell(slot, field)].toInt())

    override fun setTick(slot: Int, field: Int, value: Tick) {
        primitives[cell(slot, field)] = value.value
    }

    override fun getTick(slot: Int, field: Int): Tick = Tick(primitives[cell(slot, field)])

    override fun setObject(slot: Int, field: Int, value: Any?) {
        objects[cell(slot, field)] = value
    }

    override fun getObject(slot: Int, field: Int): Any? = objects[cell(slot, field)]

    override fun fieldEquals(slotA: Int, slotB: Int, field: Int): Boolean {
        val a = cell(slotA, field)
        val b = cell(slotB, field)
        return primitives[a] == primitives[b] && objects[a] == objects[b]
    }

    override fun copySlot(fromSlot: Int, toSlot: Int) {
        val from = cell(fromSlot, 0)
        val to = cell(toSlot, 0)
        primitives.copyInto(primitives, to, from, from + fieldCount)
        objects.copyInto(objects, to, from, from + fieldCount)
    }

    private fun cell(slot: Int, field: Int): Int {
        require(slot in 0 until slotCount) { "slot out of range: $slot (0 until $slotCount)" }
        require(field in 0 until fieldCount) { "field out of range: $field (0 until $fieldCount)" }
        return slot * fieldCount + field
    }
}
