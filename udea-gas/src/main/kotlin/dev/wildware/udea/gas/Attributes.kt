package dev.wildware.udea.gas

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.snapshot.StableHash

/**
 * One entity's attribute values: authoritative [base], derived [current].
 *
 * ## What it replaces
 *
 * `Attributes(val attributeSet: AttributeSet)` holding a `MutableMap<String, Attribute>` of
 * boxed pairs, replicated as one `@UdeaSync` property per attribute
 * (`example/.../CharacterAttributeSet.kt:20-52`). Forty attributes on a MOBA champion is forty
 * replicated fields, and `Replicator`'s mask is 64 bits *per component* — spec 7 names the dense
 * indexed array as the mitigation and this is it: one `FloatArray`, one replicated field, ids
 * from [AttributeTable].
 *
 * ## Why `current` is not captured
 *
 * `current` is a pure function of `(base, active effects)`, recomputed from scratch every tick
 * by `AttributeSystem`. Capturing it would make a snapshot carry a value that a restore could
 * disagree with, and it is exactly that derived-value property that makes a mispredicted
 * rollback incapable of leaving a corrupted stat behind. So the snapshot carries `base` and the
 * effect list, and `current` reappears on the first tick after a restore.
 */
public class Attributes(
    /** The table these values are indexed by. Shared, never per entity. */
    public val table: AttributeTable,
) : Component<Attributes> {

    /** Authoritative values. Written by instant and periodic effects, and replicated. */
    public val base: FloatArray = table.newBaseArray()

    /**
     * Values after every active modifier. Derived every tick; never captured, never replicated.
     *
     * Public and mutable-by-array because the recompute writes into it in place — a defensive
     * copy per entity per tick is the allocation issue #97 exists to remove. Read it; the only
     * writer is `AttributeSystem`.
     */
    public val current: FloatArray = base.copyOf()

    override fun type(): ComponentType<Attributes> = Attributes

    /** [id]'s authoritative value. */
    public fun base(id: AttributeId): Float = base[checked(id)]

    /** Sets [id]'s authoritative value. Server-side; a client's is overwritten by replication. */
    public fun setBase(id: AttributeId, value: Float) {
        base[checked(id)] = value
    }

    /** [id]'s value after modifiers — what gameplay reads. */
    public fun current(id: AttributeId): Float = current[checked(id)]

    private fun checked(id: AttributeId): Int {
        require(id.index in base.indices) {
            "no attribute with id ${id.index}; this entity's table holds ${base.size}"
        }
        return id.index
    }

    override fun toString(): String = "Attributes(${base.size} values)"

    public companion object : ComponentType<Attributes>()
}

/**
 * An immutable snapshot of a `base` array, with a content hash.
 *
 * `FieldStore.setObject` refuses anything whose `hashCode` is an identity hash, because
 * `WorldHasher` folds an object column's hash straight into the determinism hash — a raw
 * `FloatArray` there would make two processes report a divergence for identical worlds, every
 * run. Wrapping it in a [StableHash] with a content hash over *raw bits* is what makes the
 * single-field layout legal, and raw bits rather than `Float.hashCode` so that `-0.0` and `0.0`
 * hash apart exactly as `FieldStore.fieldEquals` compares them apart.
 *
 * The copy in [of] is a real cost: one array allocation per component per capture. The proper
 * fix is a `FieldKind.FloatArray` column in `ColumnarFieldStore`, which is `udea-core`'s to add;
 * this type is what makes the dense layout work without touching the frozen `Replicator`
 * contract in the meantime.
 */
public class AttributeVector private constructor(private val values: FloatArray) : StableHash {

    /** How many attributes this vector holds. */
    public val size: Int get() = values.size

    /** The value at [index]. */
    public operator fun get(index: Int): Float = values[index]

    /** Copies these values into [target]. */
    public fun copyInto(target: FloatArray) {
        require(target.size == values.size) {
            "cannot copy $size attribute values into an array of ${target.size}"
        }
        System.arraycopy(values, 0, target, 0, values.size)
    }

    /** Raw-bit content equality, matching `FieldStore.fieldEquals`. */
    override fun equals(other: Any?): Boolean {
        if (other !is AttributeVector || other.values.size != values.size) return false
        var index = 0
        while (index < values.size) {
            if (values[index].toRawBits() != other.values[index].toRawBits()) return false
            index++
        }
        return true
    }

    /** Content hash over raw bits, stable across processes. */
    override fun hashCode(): Int {
        var hash = 1
        var index = 0
        while (index < values.size) {
            hash = 31 * hash + values[index].toRawBits()
            index++
        }
        return hash
    }

    override fun toString(): String = "AttributeVector($size)"

    public companion object {
        /** A vector holding a copy of [values]. */
        public fun of(values: FloatArray): AttributeVector = AttributeVector(values.copyOf())
    }
}
