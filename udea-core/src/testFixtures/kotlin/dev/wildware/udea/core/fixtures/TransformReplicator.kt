package dev.wildware.udea.core.fixtures

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator

/**
 * The executable specification of the `Replicator` contract, written by hand.
 *
 * Everything `udea-codegen` emits must behave exactly like this. It is committed rather than
 * generated on purpose: the contract is frozen in Phase 0, before a single game component is
 * annotated, so the specification cannot be a product of the generator it is meant to
 * constrain.
 *
 * ## Field layout
 *
 * `Transform` has three annotated properties but four fields, because `position` is a
 * composite value type and is **lowered to one field index per primitive component**:
 *
 * | index | name | mask |
 * |---|---|---|
 * | 0 | `position.x` | `@Net` — net and all |
 * | 1 | `position.y` | `@Net` — net and all |
 * | 2 | `rotation` | `@Net` — net and all |
 * | 3 | `lastGroundedTick` | `@Sim` — all only |
 *
 * Lowering is what lets one mask bit mean one comparable value, keeps the store columnar and
 * allocation-free, and keeps `fieldNames[i]` index-aligned with mask bit `i` so a desync
 * report can name the field that differs. `lastGroundedTick` sits outside [netMask]: it
 * rewinds, and it never reaches a client.
 */
public object TransformReplicator : Replicator<Transform> {

    public const val FIELD_POSITION_X: Int = 0
    public const val FIELD_POSITION_Y: Int = 1
    public const val FIELD_ROTATION: Int = 2
    public const val FIELD_LAST_GROUNDED_TICK: Int = 3

    override val typeId: ComponentTypeId = ComponentTypeId(1)

    override val fieldNames: List<String> =
        listOf("position.x", "position.y", "rotation", "lastGroundedTick")

    override val netMask: FieldMask =
        MaskOps.of(FIELD_POSITION_X, FIELD_POSITION_Y, FIELD_ROTATION)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Transform, store: FieldStore, slot: Int) {
        store.setFloat(slot, FIELD_POSITION_X, component.position.x)
        store.setFloat(slot, FIELD_POSITION_Y, component.position.y)
        store.setFloat(slot, FIELD_ROTATION, component.rotation)
        store.setTick(slot, FIELD_LAST_GROUNDED_TICK, component.lastGroundedTick)
    }

    /**
     * Bit-identical comparison, never `!=` on two `Float`s.
     *
     * `getFloat` returns a statically-typed `Float`, so `!=` would be IEEE 754: `NaN != NaN`
     * is true and `0.0f != -0.0f` is false — the opposite of `FieldStore.fieldEquals` on both
     * counts, and the contract says the two must agree. See `FieldStore.fieldEquals` for why
     * the stored representation is the semantics that converges. Every emitted `diff` must
     * compare floats this way.
     */
    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        if (store.getFloat(slotA, FIELD_POSITION_X).toRawBits() !=
            store.getFloat(slotB, FIELD_POSITION_X).toRawBits()
        ) {
            mask = MaskOps.set(mask, FIELD_POSITION_X)
        }
        if (store.getFloat(slotA, FIELD_POSITION_Y).toRawBits() !=
            store.getFloat(slotB, FIELD_POSITION_Y).toRawBits()
        ) {
            mask = MaskOps.set(mask, FIELD_POSITION_Y)
        }
        if (store.getFloat(slotA, FIELD_ROTATION).toRawBits() !=
            store.getFloat(slotB, FIELD_ROTATION).toRawBits()
        ) {
            mask = MaskOps.set(mask, FIELD_ROTATION)
        }
        if (store.getTick(slotA, FIELD_LAST_GROUNDED_TICK) !=
            store.getTick(slotB, FIELD_LAST_GROUNDED_TICK)
        ) {
            mask = MaskOps.set(mask, FIELD_LAST_GROUNDED_TICK)
        }
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        // An empty mask emits zero bits: not a mask, not a header, nothing. The framing
        // layer above never emits an entry for a component that did not change.
        if (MaskOps.isEmpty(mask)) return

        MaskOps.writeTo(mask, out, FIELD_COUNT)
        if (MaskOps.test(mask, FIELD_POSITION_X)) out.writeFloat(store.getFloat(slot, FIELD_POSITION_X))
        if (MaskOps.test(mask, FIELD_POSITION_Y)) out.writeFloat(store.getFloat(slot, FIELD_POSITION_Y))
        if (MaskOps.test(mask, FIELD_ROTATION)) out.writeFloat(store.getFloat(slot, FIELD_ROTATION))
        if (MaskOps.test(mask, FIELD_LAST_GROUNDED_TICK)) {
            out.writeLong(store.getTick(slot, FIELD_LAST_GROUNDED_TICK).value)
        }
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, FIELD_POSITION_X)) store.setFloat(slot, FIELD_POSITION_X, src.readFloat())
        if (MaskOps.test(mask, FIELD_POSITION_Y)) store.setFloat(slot, FIELD_POSITION_Y, src.readFloat())
        if (MaskOps.test(mask, FIELD_ROTATION)) store.setFloat(slot, FIELD_ROTATION, src.readFloat())
        if (MaskOps.test(mask, FIELD_LAST_GROUNDED_TICK)) {
            store.setTick(slot, FIELD_LAST_GROUNDED_TICK, Tick(src.readLong()))
        }
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Transform, mask: FieldMask) {
        // In place: the Vec2 identity is preserved, so anything holding a reference to it
        // (rendering, physics) sees the restored value without being re-wired.
        if (MaskOps.test(mask, FIELD_POSITION_X)) {
            component.position.x = store.getFloat(slot, FIELD_POSITION_X)
        }
        if (MaskOps.test(mask, FIELD_POSITION_Y)) {
            component.position.y = store.getFloat(slot, FIELD_POSITION_Y)
        }
        if (MaskOps.test(mask, FIELD_ROTATION)) {
            component.rotation = store.getFloat(slot, FIELD_ROTATION)
        }
        if (MaskOps.test(mask, FIELD_LAST_GROUNDED_TICK)) {
            component.lastGroundedTick = store.getTick(slot, FIELD_LAST_GROUNDED_TICK)
        }
    }

    override fun getField(component: Transform, fieldIndex: Int): Any? = when (fieldIndex) {
        FIELD_POSITION_X -> component.position.x
        FIELD_POSITION_Y -> component.position.y
        FIELD_ROTATION -> component.rotation
        FIELD_LAST_GROUNDED_TICK -> component.lastGroundedTick
        else -> throw NoSuchFieldIndexException(TYPE_NAME, fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Transform, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            FIELD_POSITION_X -> component.position.x = expect<Float>(fieldIndex, value)
            FIELD_POSITION_Y -> component.position.y = expect<Float>(fieldIndex, value)
            FIELD_ROTATION -> component.rotation = expect<Float>(fieldIndex, value)
            FIELD_LAST_GROUNDED_TICK -> component.lastGroundedTick = expect<Tick>(fieldIndex, value)
            else -> throw NoSuchFieldIndexException(TYPE_NAME, fieldIndex, FIELD_COUNT)
        }
    }

    private inline fun <reified V : Any> expect(fieldIndex: Int, value: Any?): V =
        value as? V ?: throw IllegalArgumentException(
            "$TYPE_NAME.${fieldNames[fieldIndex]} expects ${V::class.simpleName}, " +
                "got ${if (value == null) "null" else value::class.simpleName}",
        )

    private const val TYPE_NAME: String = "Transform"

    /** Four fields, not three: `position` lowers to two. */
    public const val FIELD_COUNT: Int = 4
}
