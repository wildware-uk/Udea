package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.FieldStore

/**
 * The real [FieldStore]: struct-of-arrays columns over pooled buffers.
 *
 * `ArrayFieldStore` in this module's test fixtures widens every field into one `Long` cell
 * and is a correctness reference, not this. This is the store the snapshot ring and — because
 * the ring **is** the replication baseline store (spec 3.1) — `udea-net`'s delta encoder both
 * write into. One store, one component type, exactly as the frozen contract says.
 *
 * ## Columnar, and why it is not an array of structs
 *
 * `common/ecs/system/NetworkServerSystem.kt:110` built a fresh `EntityUpdate` per entity per
 * tick. At 1000 entities and 60Hz that is 60 000 objects a second on the path that must not
 * allocate at all. Here one field of one component across every entity is one contiguous run
 * inside a shared primitive array:
 *
 * ```
 * floats = [ position.x for every slot | position.y for every slot | rotation for every slot ]
 * ```
 *
 * Capture writes down a column, [WorldHasher] reads down a column, [fieldEquals] compares two
 * columns. Nothing on any of those paths allocates, and the layout is a pure function of the
 * [ComponentSchema], so two independently built processes lay out identical stores.
 *
 * Four backing arrays rather than one per field: a 4-field component would otherwise be four
 * small arrays to grow, copy and account for, and [sizeBytes] would be dominated by array
 * headers rather than by data.
 *
 * ## Pooling
 *
 * [reset] keeps every backing array and only drops object references. Growth is
 * doubling-with-copy through [ensureSlots], so a warmed store never allocates again — which
 * is what [SnapshotBudgets.CAPTURE_ALLOCATED_BYTES] gates in CI.
 *
 * Not thread-safe: like the rest of the kernel, one store belongs to one simulation on one
 * thread.
 */
public class ColumnarFieldStore(
    /** The column layout. Fixed for the life of the store. */
    public val schema: ComponentSchema,
    initialSlots: Int = DEFAULT_INITIAL_SLOTS,
) : FieldStore {

    init {
        require(initialSlots > 0) { "initialSlots must be positive, was $initialSlots" }
    }

    /**
     * Rows the backing arrays can hold. Also the stride of every column, which is why growth
     * re-lays the arrays out rather than merely copying them.
     */
    override var slotCount: Int = initialSlots
        private set

    override val fieldCount: Int get() = schema.fieldCount

    private var ints: IntArray = IntArray(columns(ColumnGroup.Ints) * initialSlots)
    private var longs: LongArray = LongArray(columns(ColumnGroup.Longs) * initialSlots)
    private var floats: FloatArray = FloatArray(columns(ColumnGroup.Floats) * initialSlots)
    private var objects: Array<Any?> = arrayOfNulls(columns(ColumnGroup.Objects) * initialSlots)

    /**
     * Grows the store to hold at least [required] rows, preserving what is already stored.
     *
     * Doubling rather than exact-fit: a ring that captured one more entity each tick would
     * otherwise copy every column on every tick forever. This allocates, and is meant to — it
     * is the warm-up path, and [SnapshotBudgets] measures the steady state after it has run.
     */
    public fun ensureSlots(required: Int) {
        require(required >= 0) { "required slots must not be negative, was $required" }
        if (required <= slotCount) return

        var capacity = slotCount
        while (capacity < required) capacity *= 2

        val grownInts = regrow(ints, ColumnGroup.Ints, capacity) { IntArray(it) }
        val grownLongs = regrow(longs, ColumnGroup.Longs, capacity) { LongArray(it) }
        val grownFloats = regrow(floats, ColumnGroup.Floats, capacity) { FloatArray(it) }
        val grownObjects = regrow(objects, ColumnGroup.Objects, capacity) { arrayOfNulls(it) }

        ints = grownInts
        longs = grownLongs
        floats = grownFloats
        objects = grownObjects
        slotCount = capacity
    }

    /**
     * Readies the store for a fresh capture without giving up its buffers.
     *
     * Object cells are nulled and primitive cells are not. That is not laziness: a stale
     * object cell keeps a captured value reachable for as long as the pooled store lives,
     * which across a 720-slot ring is a leak; a stale primitive cell is unreachable, because
     * every reader is bounded by the row count the owning [WorldFieldStore] recorded and
     * nothing ever reads past it.
     */
    public fun reset() {
        if (objects.isNotEmpty()) objects.fill(null)
    }

    /** Bytes of backing array this store holds, for the ring's budget accounting. */
    public fun sizeBytes(): Long =
        ints.size.toLong() * Int.SIZE_BYTES +
            longs.size.toLong() * Long.SIZE_BYTES +
            floats.size.toLong() * Float.SIZE_BYTES +
            objects.size.toLong() * REFERENCE_BYTES +
            ARRAY_HEADER_BYTES * BACKING_ARRAYS

    // --- typed accessors -----------------------------------------------------------------

    override fun setBoolean(slot: Int, field: Int, value: Boolean) {
        ints[cellOfKind(slot, field, FieldKind.Bool)] = if (value) 1 else 0
    }

    override fun getBoolean(slot: Int, field: Int): Boolean =
        ints[cellOfKind(slot, field, FieldKind.Bool)] != 0

    override fun setInt(slot: Int, field: Int, value: Int) {
        ints[cellOfKind(slot, field, FieldKind.Int)] = value
    }

    override fun getInt(slot: Int, field: Int): Int = ints[cellOfKind(slot, field, FieldKind.Int)]

    override fun setLong(slot: Int, field: Int, value: Long) {
        longs[cellOfKind(slot, field, FieldKind.Long)] = value
    }

    override fun getLong(slot: Int, field: Int): Long = longs[cellOfKind(slot, field, FieldKind.Long)]

    override fun setFloat(slot: Int, field: Int, value: Float) {
        floats[cellOfKind(slot, field, FieldKind.Float)] = value
    }

    override fun getFloat(slot: Int, field: Int): Float =
        floats[cellOfKind(slot, field, FieldKind.Float)]

    override fun setNetId(slot: Int, field: Int, value: NetId) {
        ints[cellOfKind(slot, field, FieldKind.NetId)] = value.raw
    }

    override fun getNetId(slot: Int, field: Int): NetId =
        NetId.ofRaw(ints[cellOfKind(slot, field, FieldKind.NetId)])

    override fun setTick(slot: Int, field: Int, value: Tick) {
        longs[cellOfKind(slot, field, FieldKind.Tick)] = value.value
    }

    override fun getTick(slot: Int, field: Int): Tick =
        Tick(longs[cellOfKind(slot, field, FieldKind.Tick)])

    /**
     * Stores [value], having checked that its `hashCode` is a function of its value.
     *
     * The check is here because here is where the value first exists: a [ComponentSchema]
     * knows only that a field is an [FieldKind.Object], never what type it will hold. One
     * `instanceof` chain per stored object field, no allocation, and it turns "a component
     * with an identity-hashCode Object field makes the determinism gate report a divergence
     * that is not there" from an invisible property into a failure at the call site that
     * caused it.
     *
     * @throws IllegalArgumentException if [value] is neither a platform type with a specified
     *   `hashCode` nor a declared [StableHash].
     */
    override fun setObject(slot: Int, field: Int, value: Any?) {
        val cell = cellOfKind(slot, field, FieldKind.Object)
        require(hasStableHashCode(value)) {
            "${schema.typeName}.${schema.nameOf(field)} was given a ${value!!::class.java.name}, " +
                "whose hashCode is an identity hash. WorldHasher folds an Object column's " +
                "hashCode into the determinism hash, so this world would hash differently in " +
                "every process and the gate would report a divergence that does not exist. " +
                "Declare the type as StableHash, or lower the field to a primitive kind."
        }
        objects[cell] = value
    }

    /**
     * Whether [value]'s `hashCode` is specified by its value rather than by its address.
     *
     * `null` is fine: [hashableBits] folds a fixed sentinel for it. Enums are **not** — see
     * [StableHash].
     */
    private fun hasStableHashCode(value: Any?): Boolean = when (value) {
        null, is String, is StableHash,
        is Int, is Long, is Short, is Byte, is Char, is Boolean, is Float, is Double,
        -> true

        else -> false
    }

    override fun getObject(slot: Int, field: Int): Any? =
        objects[cellOfKind(slot, field, FieldKind.Object)]

    /**
     * Bit-identical comparison, per `FieldStore.fieldEquals`.
     *
     * Floats are compared through `Float.toRawBits`, so `NaN` equals itself and `-0.0f`
     * differs from `0.0f` — the opposite of `==` on both counts, and the only semantics under
     * which a delta converges. [WorldHasher] deliberately canonicalises instead; see
     * [FieldComparison] for the one place the two differ and why.
     */
    override fun fieldEquals(slotA: Int, slotB: Int, field: Int): Boolean =
        fieldEquals(slotA, this, slotB, field, FieldComparison.Bitwise)

    override fun copySlot(fromSlot: Int, toSlot: Int) {
        for (field in 0 until fieldCount) {
            val from = cell(fromSlot, field)
            val to = cell(toSlot, field)
            when (schema.kindOf(field).group) {
                ColumnGroup.Ints -> ints[to] = ints[from]
                ColumnGroup.Longs -> longs[to] = longs[from]
                ColumnGroup.Floats -> floats[to] = floats[from]
                ColumnGroup.Objects -> objects[to] = objects[from]
            }
        }
    }

    /**
     * Compares one field of [slotA] here against [slotB] of [other], which must share this
     * store's schema.
     *
     * The cross-store form is what a diff actually needs: two ring slots are two stores, and
     * the frozen single-store `fieldEquals` cannot see across them.
     */
    public fun fieldEquals(
        slotA: Int,
        other: ColumnarFieldStore,
        slotB: Int,
        field: Int,
        comparison: FieldComparison,
    ): Boolean {
        require(other.schema === schema) {
            "cannot compare ${schema.typeName} against ${other.schema.typeName}: different schemas"
        }
        val a = cell(slotA, field)
        val b = other.cell(slotB, field)
        return when (schema.kindOf(field).group) {
            ColumnGroup.Ints -> ints[a] == other.ints[b]
            ColumnGroup.Longs -> longs[a] == other.longs[b]
            ColumnGroup.Floats -> comparison.floatBits(floats[a]) == comparison.floatBits(other.floats[b])
            ColumnGroup.Objects -> objects[a] == other.objects[b]
        }
    }

    /**
     * The stored bits of `(slot, field)`, normalised by [comparison]. What [WorldHasher] folds.
     *
     * An `Object` field contributes its `hashCode`, which is why [FieldKind.Object] carries a
     * stable-hash obligation: `Any.hashCode` on an ordinary class is an address, and hashing
     * one would make the determinism gate fail on every second run for no reason at all.
     */
    internal fun hashableBits(slot: Int, field: Int, comparison: FieldComparison): Long {
        val at = cell(slot, field)
        return when (schema.kindOf(field).group) {
            ColumnGroup.Ints -> ints[at].toLong()
            ColumnGroup.Longs -> longs[at]
            ColumnGroup.Floats -> comparison.floatBits(floats[at]).toLong()
            ColumnGroup.Objects -> (objects[at]?.hashCode() ?: OBJECT_NULL_HASH).toLong()
        }
    }

    /**
     * The value at `(slot, field)`, boxed, for diagnostics.
     *
     * Boxing is acceptable here for the same reason it is on `Replicator.getField`: this runs
     * once per reported divergence or per agent tool call, never once per entity per tick.
     * A `DivergenceReport` that named a field but not the two values it disagreed about would
     * send its reader straight back to the debugger.
     */
    public fun valueAt(slot: Int, field: Int): Any? = when (schema.kindOf(field)) {
        FieldKind.Bool -> getBoolean(slot, field)
        FieldKind.Int -> getInt(slot, field)
        FieldKind.Long -> getLong(slot, field)
        FieldKind.Float -> getFloat(slot, field)
        FieldKind.NetId -> getNetId(slot, field)
        FieldKind.Tick -> getTick(slot, field)
        FieldKind.Object -> getObject(slot, field)
    }

    /** Index of `(slot, field)` inside its group's backing array. */
    private fun cell(slot: Int, field: Int): Int {
        require(slot in 0 until slotCount) { "slot out of range: $slot (0 until $slotCount)" }
        require(field in 0 until fieldCount) { "field out of range: $field (0 until $fieldCount)" }
        return schema.columnIndex[field] * slotCount + slot
    }

    /**
     * [cell], having first checked the field really is of [expected] kind.
     *
     * `setInt` on a `NetId` field would land in the same backing array and read back
     * plausibly, so nothing downstream would notice until a restored entity referenced a
     * different one. The kinds are known here, so the mistake is worth naming here.
     */
    private fun cellOfKind(slot: Int, field: Int, expected: FieldKind): Int {
        val actual = schema.kindOf(field)
        require(actual == expected) {
            "${schema.typeName}.${schema.nameOf(field)} is a $actual field, accessed as $expected"
        }
        return cell(slot, field)
    }

    private fun columns(group: ColumnGroup): Int = schema.columnsPerGroup[group.ordinal]

    /**
     * Re-lays [current] out at a wider stride, column by column.
     *
     * A plain `copyOf` would be wrong: the stride *is* the capacity, so widening it moves
     * every column but the first, and a straight copy would leave field 1's values where
     * field 0's second half now lives.
     */
    private inline fun <A> regrow(
        current: A,
        group: ColumnGroup,
        capacity: Int,
        allocate: (Int) -> A,
    ): A where A : Any {
        val count = columns(group)
        if (count == 0) return current
        val grown = allocate(count * capacity)
        for (column in 0 until count) {
            System.arraycopy(current, column * slotCount, grown, column * capacity, slotCount)
        }
        return grown
    }

    override fun toString(): String =
        "ColumnarFieldStore(${schema.typeName}, slots=$slotCount, fields=$fieldCount)"

    public companion object {
        /** Enough for a small scene without a regrow; ring stores then sit at their high water. */
        public const val DEFAULT_INITIAL_SLOTS: Int = 64

        /** Compressed-oops reference width. The ring budget is an accounting figure, not a `sizeof`. */
        private const val REFERENCE_BYTES: Int = 4

        /** JVM array header: a 12-byte object header plus a 4-byte length. */
        private const val ARRAY_HEADER_BYTES: Long = 16L

        /** `ints`, `longs`, `floats`, `objects`. */
        private const val BACKING_ARRAYS: Long = 4L

        /**
         * What a null reference contributes to the hash.
         *
         * Not `0`: an object whose `hashCode` is `0` would then be indistinguishable from an
         * absent one, and "the field was cleared" is exactly the kind of change a determinism
         * gate exists to catch.
         */
        private const val OBJECT_NULL_HASH: Int = 0x9E3779B9.toInt()
    }
}

/**
 * How two `Float` fields are compared.
 *
 * The engine needs both semantics and they genuinely disagree, so the choice is a parameter
 * rather than a constant somebody later "fixes".
 */
public enum class FieldComparison {

    /**
     * Compares stored representations, per `FieldStore.fieldEquals`.
     *
     * `NaN` equals itself and `-0.0f` differs from `0.0f`. This is what a delta encoder needs:
     * what is reported as different is exactly what a write would change, so a delta
     * converges and a baseline settles.
     */
    Bitwise {
        override fun floatBits(value: Float): Int = value.toRawBits()
    },

    /**
     * Compares after normalising `-0.0f` to `0.0f` and every `NaN` payload to one canonical
     * `NaN`.
     *
     * This is what the determinism gate needs, and only it. Two JVMs that computed the same
     * value may still disagree about which `NaN` payload they produced or about the sign of a
     * zero, and reporting that as divergence buries a real defect under benign noise (spec 7,
     * "cross-JVM float differences"). It must never be used for a delta: under it
     * `0.0f -> -0.0f` is not a change, so nothing would be sent and the destination would keep
     * the wrong sign forever.
     */
    Canonical {
        override fun floatBits(value: Float): Int = when {
            value.isNaN() -> CANONICAL_NAN_BITS
            value == 0f -> 0
            else -> value.toRawBits()
        }
    },
    ;

    /** [value]'s bits under this comparison's normalisation. */
    internal abstract fun floatBits(value: Float): Int

    private companion object {
        /** `Float.NaN`'s own bit pattern: the one every other payload folds onto. */
        val CANONICAL_NAN_BITS: Int = Float.NaN.toRawBits()
    }
}
