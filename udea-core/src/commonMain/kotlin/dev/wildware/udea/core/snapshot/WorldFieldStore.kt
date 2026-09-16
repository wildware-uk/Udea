package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.identity.NetId

/**
 * One whole world's captured fields: every entity, every replicated component, one tick.
 *
 * The frozen `FieldStore` is *per component type* — "one store is one component type" — so
 * something has to hold the other two axes, and this is it. A row is one entity; a column
 * group is one component type's [ColumnarFieldStore]; a presence bit says whether that entity
 * carried that component at capture time.
 *
 * ```
 * row 0  NetId(#0@0)   presence 0b101   Movement -> slot 0    Link -> slot 0
 * row 1  NetId(#1@0)   presence 0b001   Movement -> slot 1
 * row 2  NetId(#4@2)   presence 0b111   Movement -> slot 2    Health -> slot 1    Link -> slot 1
 * ```
 *
 * Note that a component's slot is **not** the row: a store only holds rows for the entities
 * that actually carry the component, packed densely, so a component on 3 of 1000 entities
 * costs three slots and not a thousand. [componentSlotAt] is the row-to-slot mapping.
 *
 * ## Rows are in ascending NetId order, always
 *
 * `NetIdIndex.forEachLive` visits ascending index, which is ascending [NetId], and capture
 * appends in that order. Two processes holding the same live set therefore build identical
 * row orders regardless of how their free lists churned — which is what makes [WorldHasher]
 * a determinism gate rather than an allocation-order detector, and what lets [diffInto] be a
 * linear merge instead of a lookup per entity.
 *
 * ## Pooled
 *
 * [reset] keeps every array and every component store. A ring slot is recycled, never
 * reallocated (spec 7: "no per-client shadow copies"), which is the whole reason capture can
 * be allocation-free after warm-up.
 */
public class WorldFieldStore(
    /** Which component types exist, and in what canonical order. */
    public val registry: ComponentRegistry,
    initialRows: Int = DEFAULT_INITIAL_ROWS,
) {

    init {
        require(initialRows > 0) { "initialRows must be positive, was $initialRows" }
    }

    /** How many 64-bit words one row's component-presence mask occupies. */
    private val presenceWords: Int = (registry.size + Long.SIZE_BITS - 1) / Long.SIZE_BITS

    private val stores: Array<ColumnarFieldStore> =
        Array(registry.size) { ColumnarFieldStore(registry.schemaAt(it)) }

    /** Live rows, one per captured entity, in ascending [NetId] order. */
    private var netIds: IntArray = IntArray(initialRows)

    /** Component presence per row, `presenceWords` words each. */
    private var presence: LongArray = LongArray(initialRows * presenceWords)

    /** For each row and component, the slot that component's store holds it in. */
    private var componentSlots: IntArray = IntArray(initialRows * registry.size)

    /** How many slots of each component's store are in use. */
    private val slotsUsed: IntArray = IntArray(registry.size)

    /** Rows the arrays can hold before a regrow. */
    private var rowCapacity: Int = initialRows

    /** Entities captured. */
    public var rowCount: Int = 0
        private set

    /** Drops every row and keeps every buffer. */
    public fun reset() {
        rowCount = 0
        slotsUsed.fill(0)
        presence.fill(0L)
        for (store in stores) store.reset()
    }

    /**
     * Appends a row for [netId] and returns its index.
     *
     * @throws IllegalArgumentException if [netId] does not come after the previous row's. The
     *   ascending-order invariant is what every reader here relies on, and a capture loop that
     *   quietly stopped visiting in order would present much later as a hash that differs
     *   between two machines holding the same world.
     */
    public fun appendRow(netId: NetId): Int {
        require(!netId.isNone) { "NetId.NONE names no entity and cannot be captured" }
        if (rowCount > 0) {
            val previous = NetId.ofRaw(netIds[rowCount - 1])
            require(netId > previous) {
                "rows must be appended in ascending NetId order; $netId does not follow $previous"
            }
        }
        ensureRows(rowCount + 1)
        val row = rowCount
        netIds[row] = netId.raw
        rowCount++
        return row
    }

    /**
     * Claims a slot in [componentIndex]'s store for [row] and returns it.
     *
     * Called by capture once it knows the entity really carries the component — which is why
     * this and [appendRow] are separate: presence is discovered per component, after the row
     * exists.
     */
    public fun claimSlot(row: Int, componentIndex: Int): Int {
        val store = storeAt(componentIndex)
        val slot = slotsUsed[componentIndex]
        store.ensureSlots(slot + 1)
        slotsUsed[componentIndex] = slot + 1
        componentSlots[componentCell(row, componentIndex)] = slot
        presence[row * presenceWords + (componentIndex ushr LONG_SHIFT)] =
            presence[row * presenceWords + (componentIndex ushr LONG_SHIFT)] or
            (1L shl (componentIndex and LONG_MASK))
        return slot
    }

    /** The columnar store for the component at dense [componentIndex]. */
    public fun storeAt(componentIndex: Int): ColumnarFieldStore {
        require(componentIndex in stores.indices) {
            "component index out of range: $componentIndex (0 until ${stores.size})"
        }
        return stores[componentIndex]
    }

    /** The entity [row] belongs to. */
    public fun netIdAt(row: Int): NetId = NetId.ofRaw(netIds[checkedRow(row)])

    /** True when the entity at [row] carried the component at [componentIndex]. */
    public fun isPresent(row: Int, componentIndex: Int): Boolean {
        checkedRow(row)
        require(componentIndex in stores.indices) {
            "component index out of range: $componentIndex (0 until ${stores.size})"
        }
        val word = presence[row * presenceWords + (componentIndex ushr LONG_SHIFT)]
        return (word ushr (componentIndex and LONG_MASK)) and 1L != 0L
    }

    /** How many slots of [componentIndex]'s store hold a captured component. */
    public fun slotsUsedAt(componentIndex: Int): Int {
        require(componentIndex in stores.indices) {
            "component index out of range: $componentIndex (0 until ${stores.size})"
        }
        return slotsUsed[componentIndex]
    }

    /** Words in one row's component-presence mask. [WorldHasher] folds each of them. */
    public val presenceWordCount: Int get() = presenceWords

    /** Word [word] of [row]'s component-presence mask. */
    public fun presenceWordAt(row: Int, word: Int): Long {
        checkedRow(row)
        require(word in 0 until presenceWords) {
            "presence word out of range: $word (0 until $presenceWords)"
        }
        return presence[row * presenceWords + word]
    }

    /** The slot [componentIndex]'s store holds [row]'s component in. Only valid when present. */
    public fun componentSlotAt(row: Int, componentIndex: Int): Int {
        require(isPresent(row, componentIndex)) {
            "${registry.schemaAt(componentIndex).typeName} is not present on ${netIdAt(row)}"
        }
        return componentSlots[componentCell(row, componentIndex)]
    }

    /**
     * The row holding [netId], or `-1`.
     *
     * A binary search rather than an index array keyed by `NetId.index`: that array would be
     * 65 536 entries per ring slot, which at 720 slots is 180MB of lookup table for a world of
     * a thousand entities. `log2(1000)` comparisons on a restore path is the right trade.
     */
    public fun rowOf(netId: NetId): Int {
        var low = 0
        var high = rowCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = NetId.ofRaw(netIds[mid])
            val order = candidate.compareTo(netId)
            when {
                order < 0 -> low = mid + 1
                order > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return NO_ROW
    }

    /** Bytes of backing storage, for the ring's budget. Backing arrays only, never live objects. */
    public fun sizeBytes(): Long {
        var total = netIds.size.toLong() * Int.SIZE_BYTES +
            presence.size.toLong() * Long.SIZE_BYTES +
            componentSlots.size.toLong() * Int.SIZE_BYTES +
            slotsUsed.size.toLong() * Int.SIZE_BYTES
        for (store in stores) total += store.sizeBytes()
        return total
    }

    /**
     * Records every disagreement between this store and [other] into [out], which is cleared
     * first.
     *
     * A linear merge over two ascending row lists, so it is `O(rows + fields)` and allocates
     * nothing. Three kinds of disagreement, all reported as `(netId, componentType, field)`:
     *
     * - an entity in one store and not the other — one [FieldDiff.PRESENCE] entry per
     *   component it carries, so the report names *what* the missing entity had rather than
     *   only that it was missing;
     * - a component on one side of a shared entity — one [FieldDiff.PRESENCE] entry;
     * - a field that differs — one entry naming the lowered field index.
     *
     * @return true when the two stores agree about everything.
     */
    public fun diffInto(
        other: WorldFieldStore,
        out: FieldDiff,
        comparison: FieldComparison = FieldComparison.Bitwise,
    ): Boolean {
        require(other.registry === registry) {
            "cannot diff stores built from different component registries"
        }
        out.clear()

        var mine = 0
        var theirs = 0
        while (mine < rowCount && theirs < other.rowCount) {
            val a = NetId.ofRaw(netIds[mine])
            val b = NetId.ofRaw(other.netIds[theirs])
            val order = a.compareTo(b)
            when {
                order < 0 -> {
                    reportWholeRow(mine, out)
                    mine++
                }

                order > 0 -> {
                    other.reportWholeRow(theirs, out)
                    theirs++
                }

                else -> {
                    diffRow(mine, other, theirs, a, out, comparison)
                    mine++
                    theirs++
                }
            }
        }
        while (mine < rowCount) reportWholeRow(mine++, out)
        while (theirs < other.rowCount) other.reportWholeRow(theirs++, out)

        return out.isEmpty
    }

    /** Every component of [row], as a presence difference: the whole entity is one-sided. */
    private fun reportWholeRow(row: Int, out: FieldDiff) {
        val netId = NetId.ofRaw(netIds[row])
        for (component in stores.indices) {
            if (isPresent(row, component)) {
                out.add(netId, registry.schemaAt(component).typeId, FieldDiff.PRESENCE)
            }
        }
    }

    private fun diffRow(
        row: Int,
        other: WorldFieldStore,
        otherRow: Int,
        netId: NetId,
        out: FieldDiff,
        comparison: FieldComparison,
    ) {
        for (component in stores.indices) {
            val here = isPresent(row, component)
            val there = other.isPresent(otherRow, component)
            val typeId = registry.schemaAt(component).typeId
            if (here != there) {
                out.add(netId, typeId, FieldDiff.PRESENCE)
                continue
            }
            if (!here) continue

            val store = stores[component]
            val slot = componentSlotAt(row, component)
            val otherSlot = other.componentSlotAt(otherRow, component)
            for (field in 0 until store.fieldCount) {
                if (!store.fieldEquals(slot, other.stores[component], otherSlot, field, comparison)) {
                    out.add(netId, typeId, field)
                }
            }
        }
    }

    private fun ensureRows(required: Int) {
        if (required <= rowCapacity) return
        var capacity = rowCapacity
        while (capacity < required) capacity *= 2

        netIds = netIds.copyOf(capacity)
        presence = presence.copyOf(capacity * presenceWords)
        componentSlots = componentSlots.copyOf(capacity * registry.size)
        rowCapacity = capacity
    }

    private fun componentCell(row: Int, componentIndex: Int): Int =
        checkedRow(row) * registry.size + componentIndex

    private fun checkedRow(row: Int): Int {
        require(row in 0 until rowCount) { "row out of range: $row (0 until $rowCount)" }
        return row
    }

    override fun toString(): String = "WorldFieldStore($rowCount rows, ${registry.size} component types)"

    public companion object {
        /** [rowOf]'s answer when the entity is not in this store. */
        public const val NO_ROW: Int = -1

        /** A small scene without a regrow; a captured world then sits at its high water. */
        private const val DEFAULT_INITIAL_ROWS: Int = 64

        /** `ushr` by this divides by 64: the bit index of a component inside a presence word. */
        private const val LONG_SHIFT: Int = 6
        private const val LONG_MASK: Int = Long.SIZE_BITS - 1
    }
}
