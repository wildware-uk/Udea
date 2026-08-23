package dev.wildware.udea.gas

import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator

/**
 * Replicates a whole attribute set through **one** field.
 *
 * ## The mask limit, and the mitigation
 *
 * `FieldMask` is 64 bits per component and spec 3.1 freezes the `Replicator` interface, so a
 * component may declare at most 64 lowered fields. A MOBA champion has roughly forty attributes;
 * replicating them as one field each — which is what `@UdeaSync var health: Float` per attribute
 * did — spends two thirds of the budget on one component and leaves nothing for anything else on
 * it. Spec 7's risk table names the dense indexed array as the mitigation, and this is the
 * replicator for it: field 0 is the whole `base` vector, and it is the only `@Net` field.
 *
 * ## Element-wise diffing without touching the frozen interface
 *
 * The frozen `diff`/`write` pair is field-granular — `write` is handed one slot and a mask, and
 * has no baseline to compare against — so a change to one attribute would put all forty on the
 * wire through it. [writeDelta] and [readDelta] are the element-granular pair, declared on this
 * class rather than on `Replicator`: they take both slots, write a per-element changed bitmask
 * and then only the floats that changed. Changing one attribute of forty is therefore one mask
 * word plus one float, not forty.
 *
 * That is deliberately an *addition*, not a change: the frozen methods still work and still mean
 * what they say, and a transport that only knows the frozen contract still gets correct (if
 * larger) packets.
 */
public class AttributesReplicator(
    /** The table every replicated vector is indexed by. */
    public val table: AttributeTable,
    override val typeId: ComponentTypeId = ComponentTypeId(DEFAULT_TYPE_ID),
) : Replicator<Attributes> {

    /** How many 64-bit words an element-changed mask needs for this table. */
    private val maskWords: Int = (table.count + WORD_BITS - 1) / WORD_BITS

    /** One name, because there is one field. */
    override val fieldNames: List<String> = listOf("base")

    override val netMask: FieldMask = MaskOps.single(BASE)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Attributes, store: FieldStore, slot: Int) {
        store.setObject(slot, BASE, AttributeVector.of(component.base))
    }

    override fun diff(
        store: FieldStore,
        slotA: Int,
        slotB: Int,
    ): FieldMask = if (store.fieldEquals(slotA, slotB, BASE)) MaskOps.EMPTY else MaskOps.single(BASE)

    override fun write(
        store: FieldStore,
        slot: Int,
        mask: FieldMask,
        out: BitWriter,
    ) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        val vector = vectorAt(store, slot)
        var index = 0
        while (index < table.count) {
            out.writeFloat(vector[index])
            index++
        }
    }

    override fun read(
        src: BitReader,
        store: FieldStore,
        slot: Int,
    ): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, BASE)) {
            val values = FloatArray(table.count)
            var index = 0
            while (index < values.size) {
                values[index] = src.readFloat()
                index++
            }
            store.setObject(slot, BASE, AttributeVector.of(values))
        }
        return mask
    }

    override fun apply(
        store: FieldStore,
        slot: Int,
        component: Attributes,
        mask: FieldMask,
    ) {
        if (!MaskOps.test(mask, BASE)) return
        vectorAt(store, slot).copyInto(component.base)
    }

    override fun getField(component: Attributes, fieldIndex: Int): Any? = when (fieldIndex) {
        BASE -> AttributeVector.of(component.base)
        else -> throw NoSuchFieldIndexException("Attributes", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Attributes, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            BASE -> (value as AttributeVector).copyInto(component.base)
            else -> throw NoSuchFieldIndexException("Attributes", fieldIndex, FIELD_COUNT)
        }
    }

    // --- element-granular delta ---------------------------------------------------------------

    /**
     * Writes only the elements that differ between [baselineSlot] and [slot].
     *
     * Layout: [maskWords] 64-bit words of changed-element bits, then one float per set bit in
     * ascending element order. Nothing is written when nothing changed, so an unchanged
     * component costs zero bits rather than a mask.
     *
     * @return how many elements were written. Zero means the component is unchanged.
     */
    public fun writeDelta(
        store: FieldStore,
        baselineSlot: Int,
        slot: Int,
        out: BitWriter,
    ): Int {
        val baseline = vectorAt(store, baselineSlot)
        val updated = vectorAt(store, slot)
        var changed = 0
        var word = 0
        while (word < maskWords) {
            var bits = 0L
            var offset = 0
            while (offset < WORD_BITS) {
                val index = word * WORD_BITS + offset
                if (index >= table.count) break
                if (baseline[index].toRawBits() != updated[index].toRawBits()) {
                    bits = bits or (1L shl offset)
                    changed++
                }
                offset++
            }
            out.writeLong(bits)
            word++
        }
        if (changed == 0) return 0
        var index = 0
        while (index < table.count) {
            if (baseline[index].toRawBits() != updated[index].toRawBits()) out.writeFloat(updated[index])
            index++
        }
        return changed
    }

    /**
     * Reads a [writeDelta] payload onto [target], which must hold the baseline values.
     *
     * @return how many elements were applied.
     */
    public fun readDelta(src: BitReader, target: FloatArray): Int {
        require(target.size == table.count) {
            "cannot apply a delta for ${table.count} attributes onto an array of ${target.size}"
        }
        val words = LongArray(maskWords)
        var word = 0
        var changed = 0
        while (word < maskWords) {
            val bits = src.readLong()
            words[word] = bits
            changed += java.lang.Long.bitCount(bits)
            word++
        }
        if (changed == 0) return 0
        var index = 0
        while (index < target.size) {
            if (words[index ushr WORD_SHIFT] and (1L shl index) != 0L) target[index] = src.readFloat()
            index++
        }
        return changed
    }

    private fun vectorAt(store: FieldStore, slot: Int): AttributeVector =
        store.getObject(slot, BASE) as? AttributeVector
            ?: error("slot $slot holds no captured attribute vector; capture before diffing or writing")

    public companion object {
        /** The one lowered field: the whole `base` vector. */
        public const val BASE: Int = 0

        /** One field, which is the entire point of this replicator. */
        public const val FIELD_COUNT: Int = 1

        /**
         * The component type id used when a caller does not supply one.
         *
         * Real ids come from a whole-project FQN sort in one generator (spec 5); this default
         * exists so a test or a single-module game can build a registry without inventing a
         * numbering scheme, and any assembled game passes its generated id explicitly.
         */
        public const val DEFAULT_TYPE_ID: Int = 64

        private const val WORD_BITS = 64
        private const val WORD_SHIFT = 6
    }
}
