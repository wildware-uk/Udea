package dev.wildware.udea.core.replication

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId

/**
 * Columnar storage for captured component fields, addressed by `(slot, field)`.
 *
 * One store is one component type. A **slot** is one entity's copy of that component at one
 * tick — the snapshot ring's slots are ring positions, and because the ring is also the
 * replication baseline store (spec 3.1) the same slots serve time travel, delta encoding
 * and `desync_report`. A **field** is an index into `Replicator.fieldNames`, which is the
 * same index as the corresponding [FieldMask] bit.
 *
 * ## Ownership
 *
 * `udea-core` declares this interface and nothing else. The real implementation — pooled
 * buffers, ring cadences, `diffInto`, the memory budget — belongs to the snapshot epic, and
 * it must implement *this* interface rather than declare its own, or the engine ships three
 * `FieldStore`s that disagree. `ArrayFieldStore` in this module's test fixtures is a plain
 * correctness reference, deliberately not the real thing.
 *
 * ## Typed accessors, no boxing
 *
 * Accessors are primitive-typed so capture never allocates: that is what keeps snapshot
 * capture inside its per-tick budget. [NetId] and [Tick] get their own accessors rather than
 * being written through [setInt] and [setLong] — that is spec 5's "treat `NetId` as a
 * primitive field type", so an entity-referencing field round-trips through capture, restore
 * and delta write with no special case anywhere. [setObject] exists for genuinely
 * reference-typed fields and is the only accessor that may allocate.
 *
 * A composite value type (a 2D vector, say) is lowered to one field index per primitive
 * component, with `fieldNames` carrying the dotted path. See `docs/contracts/replicator.md`.
 */
public interface FieldStore {

    /** How many entity-ticks this store can hold. */
    public val slotCount: Int

    /** How many fields the component type has. Equal to `Replicator.fieldNames.size`. */
    public val fieldCount: Int

    public fun setBoolean(slot: Int, field: Int, value: Boolean)

    public fun getBoolean(slot: Int, field: Int): Boolean

    public fun setInt(slot: Int, field: Int, value: Int)

    public fun getInt(slot: Int, field: Int): Int

    public fun setLong(slot: Int, field: Int, value: Long)

    public fun getLong(slot: Int, field: Int): Long

    public fun setFloat(slot: Int, field: Int, value: Float)

    public fun getFloat(slot: Int, field: Int): Float

    public fun setNetId(slot: Int, field: Int, value: NetId)

    public fun getNetId(slot: Int, field: Int): NetId

    public fun setTick(slot: Int, field: Int, value: Tick)

    public fun getTick(slot: Int, field: Int): Tick

    /** For genuinely reference-typed fields. The only accessor permitted to allocate. */
    public fun setObject(slot: Int, field: Int, value: Any?)

    public fun getObject(slot: Int, field: Int): Any?

    /**
     * Type-agnostic comparison of one field across two slots.
     *
     * This is what `desync_report(tick)` walks: it can name the differing field without
     * knowing the component's Kotlin types, because comparison happens against the stored
     * representation. A generated `Replicator.diff` uses the typed getters instead, which is
     * faster; both must agree.
     */
    public fun fieldEquals(slotA: Int, slotB: Int, field: Int): Boolean

    /** Copies every field of [fromSlot] over [toSlot]. Used to seed a baseline. */
    public fun copySlot(fromSlot: Int, toSlot: Int)
}
