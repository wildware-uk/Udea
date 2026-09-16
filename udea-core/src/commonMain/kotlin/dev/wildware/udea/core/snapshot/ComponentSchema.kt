package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.Replicator

/**
 * The storage class of one lowered field.
 *
 * A [ColumnarFieldStore] is struct-of-arrays: one primitive array per storage class, one
 * contiguous column per field inside it. To lay those columns out it has to know, before the
 * first capture, what each field is — and [Replicator] deliberately does not say, because
 * `fieldNames` is the only per-field metadata the frozen contract carries.
 *
 * So the kinds arrive alongside the replicator in a [ComponentSchema]. That is not a second
 * source of truth about the component: [ComponentSchema.of] refuses a kind list whose length
 * disagrees with `replicator.fieldNames`, which is the index-alignment invariant the whole
 * contract rests on, checked at construction rather than discovered as a corrupt snapshot.
 *
 * `NetId` and `Tick` are their own kinds rather than [Int] and [Long] for the same reason
 * [FieldStore] gives them their own accessors: an entity reference stored through `setInt`
 * is a reference the store cannot tell from a counter.
 */
public enum class FieldKind(
    /** Which backing array a field of this kind lives in. */
    internal val group: ColumnGroup,
) {
    Bool(ColumnGroup.Ints),
    Int(ColumnGroup.Ints),
    Long(ColumnGroup.Longs),
    Float(ColumnGroup.Floats),
    NetId(ColumnGroup.Ints),
    Tick(ColumnGroup.Longs),

    /**
     * A reference-typed field. Deeply immutable by [FieldStore.setObject]'s contract, and it
     * must have a `hashCode` that is stable across JVMs — [WorldHasher] folds it into the
     * determinism hash, and `Any.hashCode` on an ordinary class is an address.
     *
     * That obligation is **checked**, not documented: [ColumnarFieldStore.setObject] refuses a
     * value that is neither a platform type with a specified `hashCode` nor a declared
     * [StableHash]. See [StableHash] for why it has to be checked there and not here.
     */
    Object(ColumnGroup.Objects),
}

/**
 * A reference type whose `hashCode` is a function of its value and nothing else.
 *
 * [WorldHasher] folds an [FieldKind.Object] column's `hashCode` straight into the determinism
 * hash. `Any.hashCode` on an ordinary class is an identity hash — an address, freshly random
 * in every process — so a single component field holding one would make two processes report
 * a divergence for a world that is bit-identical, on every run, with the report pointing at
 * the field as though the *simulation* had diverged. That is worse than no gate.
 *
 * Declaring it is the opt-in. A marker interface rather than a check in [ComponentSchema.of]
 * because a schema knows only that a field is an `Object`, never what type it will hold:
 * `Replicator.fieldNames` is the only per-field metadata the frozen contract carries, so the
 * declared type is not available at construction and the value is the only thing that can be
 * checked. [ColumnarFieldStore.setObject] does it, once per stored value.
 *
 * Not needed for `String`, or for a boxed primitive: the platform specifies `hashCode` for
 * both. Deliberately **not** granted to enums — `java.lang.Enum` does not override `hashCode`,
 * so an enum constant hashes to its address like anything else, and an enum-typed field must
 * either implement this or be lowered to its `ordinal` as an [FieldKind.Int].
 */
public interface StableHash

/** The four backing arrays a [ColumnarFieldStore] pools. */
internal enum class ColumnGroup { Ints, Longs, Floats, Objects }

/**
 * The column layout of one component type: what its fields are, and where each one lives.
 *
 * Hand-written today, emitted by `udea-codegen` beside the `Replicator` later — which is why
 * [of] takes the replicator and derives everything it can from it rather than accepting a
 * parallel field-name list that could drift.
 *
 * Column indices are assigned by ascending field index within each [ColumnGroup], so the
 * layout is a pure function of the kind list and two independently built processes agree on
 * it. That matters because [WorldHasher] hashes columns in field order and a store whose
 * layout depended on registration order would hash differently on two machines holding the
 * same world.
 */
public class ComponentSchema private constructor(
    /** The stable id [Replicator.typeId] carries. Canonical ordering everywhere is by this. */
    public val typeId: ComponentTypeId,
    /** Human-readable component name, for diagnostics and divergence reports. */
    public val typeName: String,
    /** Index-aligned with [Replicator.fieldNames], mask bit `i` and store field index `i`. */
    public val fieldNames: List<String>,
    private val kinds: Array<FieldKind>,
    /** Position of each field inside its group's backing array. */
    internal val columnIndex: IntArray,
    /** How many columns each group holds, indexed by [ColumnGroup.ordinal]. */
    internal val columnsPerGroup: IntArray,
) {

    /** Lowered fields, not annotated properties: `position` counts twice. */
    public val fieldCount: Int get() = kinds.size

    /** The storage class of [field]. */
    public fun kindOf(field: Int): FieldKind {
        require(field in kinds.indices) {
            "$typeName has no field at index $field (valid indices are 0 until ${kinds.size})"
        }
        return kinds[field]
    }

    /** The name of [field], as `desync_report` and `describe_entity` print it. */
    public fun nameOf(field: Int): String = fieldNames[field]

    override fun toString(): String = "ComponentSchema($typeName, $fieldCount fields)"

    public companion object {

        /**
         * The schema for [replicator], with one [FieldKind] per lowered field.
         *
         * @throws IllegalArgumentException if [kinds] is not exactly as long as
         *   `replicator.fieldNames`. That is the index-alignment invariant from
         *   `docs/contracts/replicator.md`: `fieldNames[i]`, mask bit `i` and store field `i`
         *   are the same index, and a schema one entry short would silently shift every
         *   column after the missing one — a snapshot that restores the wrong values into the
         *   wrong fields, with nothing in the type system to catch it.
         */
        public fun of(
            replicator: Replicator<*>,
            typeName: String,
            kinds: List<FieldKind>,
        ): ComponentSchema {
            val names = replicator.fieldNames
            require(kinds.size == names.size) {
                "$typeName declares ${names.size} field name(s) but ${kinds.size} field kind(s); " +
                    "fieldNames[i], FieldMask bit i and FieldStore field i must be the same index"
            }
            require(names.isNotEmpty()) { "$typeName has no fields; a component with none cannot replicate" }
            require(names.size <= MaskOps.MAX_FIELDS) {
                "$typeName has ${names.size} lowered fields, over the ${MaskOps.MAX_FIELDS}-field " +
                    "mask limit; split the component"
            }
            require(MaskOps.containsAll(replicator.allMask, replicator.netMask)) {
                "$typeName has a netMask bit outside its allMask, which is a contradiction: " +
                    "every replicated field is also snapshotted"
            }

            val columnsPerGroup = IntArray(ColumnGroup.entries.size)
            val columnIndex = IntArray(kinds.size)
            for (field in kinds.indices) {
                val group = kinds[field].group.ordinal
                columnIndex[field] = columnsPerGroup[group]
                columnsPerGroup[group]++
            }

            return ComponentSchema(
                typeId = replicator.typeId,
                typeName = typeName,
                fieldNames = names,
                kinds = kinds.toTypedArray(),
                columnIndex = columnIndex,
                columnsPerGroup = columnsPerGroup,
            )
        }
    }
}
