package dev.wildware.udea.net.wire

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.snapshot.ColumnarFieldStore
import dev.wildware.udea.core.snapshot.ComponentRegistry

/**
 * The client's mirror of the replicated world: what it believes, per [NetId], per component.
 *
 * ## Why this is not a `WorldFieldStore`
 *
 * `udea-core`'s `WorldFieldStore` is the *server's* structure and is append-only in ascending
 * `NetId` order, because a capture visits a whole world once per tick in a fixed order. A
 * client receives a scattered subset — entity 40 updates, entity 7 is destroyed, entity 91 is
 * created — in whatever order relevancy and the budget produced. Random-access insert and
 * remove is the actual requirement, so this is a different structure with the same columns,
 * not a second copy of the same one.
 *
 * The *server* side does not get one of these. Its baseline store is the snapshot ring (spec
 * 3.1), exactly as issue #107 requires; adding a per-client shadow world would be the ~25MB
 * mistake spec section 7 calls out.
 *
 * ## One store, applied in place
 *
 * There is exactly one of these per client, and deltas are applied straight into it. That is
 * correct **because baselines are per entity** (issue #107): the server diffs an entity against
 * the newest packet containing it that the client acknowledged, which is by definition the last
 * value the client was told — the value sitting in this store right now.
 *
 * A whole-world rebuild from one named baseline tick would be *wrong* here, not merely slower. A
 * bandwidth budget means an entity can be absent from a packet while its neighbours are in it, so
 * the client's state at a given tick is not the server's world at that tick, and rebuilding from
 * it would apply every entity's delta to a baseline the server never diffed against.
 *
 * The server's baseline is behind this store by a round trip, because it moves on an *ack* and
 * this store moves on an *apply*. A field that changed and changed back inside that gap would be
 * omitted from the delta as "unchanged since the baseline" and left stale here for ever - which
 * is not a small hole, it is the one that stopped the stack converging under loss. It is closed
 * on the server side: `SnapshotWriter.writeUpdate` diffs against the acked baseline **and every
 * unacknowledged send since**, so whichever of those states this store is holding, every field
 * that could be wrong is written with an absolute value. Nothing here has to guess.
 */
public class ReplicaStore(

    /** The component types this store has columns for. Shared with the server's registry. */
    public val registry: ComponentRegistry,
) {

    private val stores: Array<ColumnarFieldStore> =
        Array(registry.size) { ColumnarFieldStore(registry.schemaAt(it)) }

    /** Row per `NetId.index`, or [ABSENT]. Grows to the highest index actually seen. */
    private var rowByIndex = IntArray(INITIAL_INDICES) { ABSENT }

    private var netIds = IntArray(INITIAL_ROWS)
    private var slots = IntArray(INITIAL_ROWS * registry.size) { ABSENT }
    private var rowCapacity = INITIAL_ROWS

    /** Rows whose entity was destroyed, available for reuse. */
    private var freeRows = IntArray(INITIAL_ROWS)
    private var freeRowCount = 0

    /** Per component: slots whose owner was destroyed, available for reuse. */
    private val freeSlots: Array<IntArray> = Array(registry.size) { IntArray(INITIAL_ROWS) }
    private val freeSlotCount = IntArray(registry.size)
    private val slotHighWater = IntArray(registry.size)

    /** Rows in use, live and free. Only meaningful for iteration bounds. */
    public var rowHighWater: Int = 0
        private set

    /** How many entities this store believes exist. */
    public var entityCount: Int = 0
        private set

    /** The row holding [netId], or [ABSENT]. Generation-checked: a stale id resolves to absent. */
    public fun rowOf(netId: NetId): Int {
        val index = netId.index
        if (index >= rowByIndex.size) return ABSENT
        val row = rowByIndex[index]
        if (row == ABSENT) return ABSENT
        return if (NetId.ofRaw(netIds[row]) == netId) row else ABSENT
    }

    /** Whether [netId] is present, at that exact generation. */
    public operator fun contains(netId: NetId): Boolean = rowOf(netId) != ABSENT

    /** The [NetId] at [row]. */
    public fun netIdAt(row: Int): NetId = NetId.ofRaw(netIds[row])

    /** Whether [row] is a live entity rather than a recycled hole. */
    public fun isLive(row: Int): Boolean =
        row in 0 until rowHighWater && netIds[row] != NetId.NONE.raw

    /**
     * The row for [netId], creating it if absent.
     *
     * A create for an index that already holds a **different generation** replaces it outright:
     * that is a recycled id, and keeping the old row would alias two entities onto one index —
     * the exact use-after-free `NetId`'s generation byte exists to make detectable.
     */
    public fun createRow(netId: NetId): Int {
        val existing = rowOf(netId)
        if (existing != ABSENT) return existing
        val index = netId.index
        ensureIndices(index + 1)
        val stale = rowByIndex[index]
        if (stale != ABSENT) removeRow(stale)

        val row = if (freeRowCount > 0) freeRows[--freeRowCount] else allocateRow()
        netIds[row] = netId.raw
        for (component in 0 until registry.size) slots[row * registry.size + component] = ABSENT
        rowByIndex[index] = row
        entityCount++
        return row
    }

    /** Removes [netId] and frees its slots. Returns false if it was not present. */
    public fun destroy(netId: NetId): Boolean {
        val row = rowOf(netId)
        if (row == ABSENT) return false
        removeRow(row)
        return true
    }

    /** The slot of [componentIndex] on [row], or [ABSENT]. */
    public fun slotOf(row: Int, componentIndex: Int): Int = slots[row * registry.size + componentIndex]

    /** The slot of [componentIndex] on [row], allocating one if the component is new. */
    public fun claimSlot(row: Int, componentIndex: Int): Int {
        val cell = row * registry.size + componentIndex
        val existing = slots[cell]
        if (existing != ABSENT) return existing
        val slot = if (freeSlotCount[componentIndex] > 0) {
            freeSlots[componentIndex][--freeSlotCount[componentIndex]]
        } else {
            val next = slotHighWater[componentIndex]++
            stores[componentIndex].ensureSlots(next + 1)
            next
        }
        slots[cell] = slot
        return slot
    }

    /**
     * Drops [componentIndex] from [row], returning its slot to the free list.
     *
     * The receiving half of the component removal record. Without it the wire can say "this
     * component is gone" and the client cannot act on it: a component the server dropped - a
     * `Combatant` on a dead unit - stays attached to the client's copy for the rest of the
     * session, and every later comparison reports it.
     *
     * @return true when the component was present.
     */
    public fun releaseSlot(row: Int, componentIndex: Int): Boolean {
        val cell = row * registry.size + componentIndex
        val slot = slots[cell]
        if (slot == ABSENT) return false
        if (freeSlotCount[componentIndex] == freeSlots[componentIndex].size) {
            freeSlots[componentIndex] = freeSlots[componentIndex].copyOf(freeSlots[componentIndex].size * 2)
        }
        freeSlots[componentIndex][freeSlotCount[componentIndex]++] = slot
        slots[cell] = ABSENT
        return true
    }

    /** The columnar store for the component at dense [componentIndex]. */
    public fun storeAt(componentIndex: Int): ColumnarFieldStore = stores[componentIndex]

    /** Drops every entity, keeping every buffer. */
    public fun clear() {
        rowByIndex.fill(ABSENT)
        netIds.fill(NetId.NONE.raw)
        slots.fill(ABSENT)
        rowHighWater = 0
        entityCount = 0
        freeRowCount = 0
        freeSlotCount.fill(0)
        slotHighWater.fill(0)
        for (store in stores) store.reset()
    }

    /** Every live [NetId], ascending. Allocates: a diagnostic and test helper, not a hot path. */
    public fun liveNetIds(): List<NetId> {
        val result = ArrayList<NetId>(entityCount)
        for (row in 0 until rowHighWater) if (isLive(row)) result += netIdAt(row)
        result.sort()
        return result
    }

    private fun removeRow(row: Int) {
        val netId = NetId.ofRaw(netIds[row])
        if (netId.isNone) return
        for (component in 0 until registry.size) {
            val cell = row * registry.size + component
            val slot = slots[cell]
            if (slot != ABSENT) {
                if (freeSlotCount[component] == freeSlots[component].size) {
                    freeSlots[component] = freeSlots[component].copyOf(freeSlots[component].size * 2)
                }
                freeSlots[component][freeSlotCount[component]++] = slot
                slots[cell] = ABSENT
            }
        }
        rowByIndex[netId.index] = ABSENT
        netIds[row] = NetId.NONE.raw
        if (freeRowCount == freeRows.size) freeRows = freeRows.copyOf(freeRows.size * 2)
        freeRows[freeRowCount++] = row
        entityCount--
    }

    private fun allocateRow(): Int {
        ensureRows(rowHighWater + 1)
        return rowHighWater++
    }

    private fun ensureRows(required: Int) {
        if (required <= rowCapacity) return
        var capacity = rowCapacity
        while (capacity < required) capacity *= 2
        netIds = netIds.copyOf(capacity)
        val widened = IntArray(capacity * registry.size) { ABSENT }
        System.arraycopy(slots, 0, widened, 0, slots.size)
        slots = widened
        rowCapacity = capacity
    }

    private fun ensureIndices(required: Int) {
        if (required <= rowByIndex.size) return
        var capacity = rowByIndex.size
        while (capacity < required) capacity *= 2
        val widened = IntArray(capacity) { ABSENT }
        System.arraycopy(rowByIndex, 0, widened, 0, rowByIndex.size)
        rowByIndex = widened
    }

    public companion object {

        /** "No row" and "no slot". */
        public const val ABSENT: Int = -1

        private const val INITIAL_ROWS: Int = 64
        private const val INITIAL_INDICES: Int = 128
    }
}
