package dev.wildware.udea.core.replication

/**
 * The one generated artefact per component (spec 3.1), serving five consumers at once:
 *
 * 1. **network delta write** — [diff] against last tick, intersected with [netMask], then [write];
 * 2. **network full write** — [write] with [netMask] and no baseline;
 * 3. **snapshot capture** — [capture] into the ring, which uses [allMask];
 * 4. **snapshot restore** — [apply] back onto a live component, in place;
 * 5. **the agent's field access** — [getField] / [setField] behind `describe_entity` and
 *    `set_component_field`.
 *
 * There is no `@Snapshotted`, no separate `SnapshotCodec` and no reflective fallback,
 * because a second mechanism is a second thing that can disagree about what an entity is.
 * The snapshot ring **is** the replication baseline store, so time travel and delta encoding
 * are one mechanism; and because [getField] exists, the MCP surface needs no reflection and
 * survives R8.
 *
 * ## The two masks
 *
 * Not everything snapshotted should be replicated: jungle respawn timers and bot blackboards
 * must rewind but must never reach a client. One annotation family, two masks:
 *
 * - `@Net` fields are replicated **and** snapshotted — they are in [netMask] and [allMask];
 * - `@Sim` fields are snapshotted only — they are in [allMask] but not [netMask].
 *
 * Both land in the same [FieldStore]. Delta write considers only [netMask]; capture uses
 * [allMask]. A field in [netMask] but not [allMask] is a contradiction and generators must
 * not emit one.
 *
 * ## Index alignment
 *
 * `fieldNames[i]` names the field at [FieldMask] bit `i`, and at [FieldStore] field index
 * `i`. All three are the same index. `desync_report(tick)` depends on it: it reports a
 * differing tick *and field*, which it can only do by naming `fieldNames[i]` for each set
 * bit of the difference between two slots.
 *
 * ## Masks are opaque
 *
 * Every parameter and return that means "a set of fields" is a [FieldMask], never a `Long`,
 * and no implementation may store one in game state. That is what makes widening the mask to
 * more than 64 fields a non-breaking change (see [FieldMask]).
 *
 * ## In-place
 *
 * [apply] mutates the caller's component instead of returning a new one, which is the one
 * idea worth keeping from the old `InPlaceSerializer<T>`: restoring 1000 entities must not
 * allocate 1000 components, and a `Vector2` mutated in place keeps the identity that
 * rendering and physics hold references to.
 *
 * ## Implementations are generated
 *
 * `udea-codegen` emits one of these per `@Replicated` component. The hand-written
 * `TransformReplicator` in this module's test fixtures is the executable specification of
 * the contract, not a production implementation.
 */
public interface Replicator<T> {

    /**
     * Stable id for the component type, assigned by the one generator from sorted FQNs and
     * pinned in `net-protocol.lock` (spec 5, "Id assignment"). See [ComponentTypeId] for why
     * it is not a bare `Int`.
     */
    public val typeId: ComponentTypeId

    /**
     * Field names, index-aligned with [FieldMask] bit positions and [FieldStore] field
     * indices. A composite value type contributes one entry per primitive component, with a
     * dotted path (`"position.x"`).
     *
     * A read-only `List` rather than an `Array`: every implementation is a Kotlin `object`,
     * so an `Array` here would be one process-wide mutable array handed to every consumer,
     * and `fieldNames[0] = "x"` would permanently corrupt the naming that `desync_report` and
     * `describe_entity` index into. It is built once at class init, so nothing on a per-tick
     * path pays for it — and a `List` compares by value, which an `Array` does not.
     */
    public val fieldNames: List<String>

    /** Fields that are replicated and snapshotted: the `@Net` set. Always a subset of [allMask]. */
    public val netMask: FieldMask

    /** Every field the component has: `@Net` plus `@Sim`. What capture writes. */
    public val allMask: FieldMask

    /** Reads every field of [component] into `store[slot]`. Must not allocate. */
    public fun capture(component: T, store: FieldStore, slot: Int)

    /**
     * Fields that differ between two captured slots, across [allMask].
     *
     * Capture-and-diff, never setter instrumentation (spec 5, "Dirty determination"):
     * `Transform.position` is a mutable vector mutated by `position.set(...)` and by physics
     * write-back, so no setter ever fires for the field that matters most, and dirty-on-assign
     * would silently under-replicate position.
     *
     * A delta write intersects the result with [netMask]; a desync report uses it whole.
     */
    public fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask

    /**
     * Writes the fields of [mask] from `store[slot]` to [out]: the mask itself, then each
     * selected field in ascending index order.
     *
     * **An empty [mask] writes zero bits.** Nothing is emitted at all — not a mask, not a
     * header — so a component that did not change costs nothing, and the framing layer above
     * is responsible for never emitting an entry for an empty delta. [read] is correspondingly
     * only ever called where the framing layer knows a payload is present.
     */
    public fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter)

    /**
     * Reads a payload written by [write] into `store[slot]` and returns the mask that was on
     * the wire. Fields outside that mask are left untouched, which is what makes a delta a
     * delta: the slot must already hold the baseline.
     */
    public fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask

    /**
     * Copies the fields of [mask] from `store[slot]` onto [component], **in place**. Fields
     * outside [mask] are left alone.
     */
    public fun apply(store: FieldStore, slot: Int, component: T, mask: FieldMask)

    /**
     * The value of field [fieldIndex] on [component], boxed. Backs `describe_entity`.
     *
     * Boxing is acceptable here and nowhere else: this path runs once per agent tool call,
     * not once per entity per tick.
     *
     * @throws NoSuchFieldIndexException if [fieldIndex] is not a valid index into [fieldNames].
     */
    public fun getField(component: T, fieldIndex: Int): Any?

    /**
     * Writes [value] into field [fieldIndex] of [component]. Backs `set_component_field`.
     *
     * @throws NoSuchFieldIndexException if [fieldIndex] is not a valid index into [fieldNames].
     * @throws IllegalArgumentException if [value] is not assignable to the field's type.
     */
    public fun setField(component: T, fieldIndex: Int, value: Any?)
}

/**
 * A field index that does not exist on a component type.
 *
 * Typed rather than an `ArrayIndexOutOfBoundsException` because the usual source is an agent
 * tool call with a bad index, and the agent needs to be told the type, the index and the
 * valid range — not a stack trace through generated code.
 */
public class NoSuchFieldIndexException(
    public val typeName: String,
    public val fieldIndex: Int,
    public val fieldCount: Int,
) : IndexOutOfBoundsException(
    "$typeName has no field at index $fieldIndex (valid indices are 0 until $fieldCount)",
)
