package dev.wildware.udea.replay.equality.fixture

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.fleksComponentType

/**
 * The two components of the `replay-equality` fixture world.
 *
 * ## Why this world exists and is not `moba`
 *
 * `ReplayEngineTest` already argues the general case: the real game answers "does the game
 * reproduce" and a small world answers "does the machinery report the truth", and only the second
 * one can be perturbed by exactly one ulp on exactly one field at exactly one tick.
 *
 * There is a second, sharper reason here. A checked-in `.udearep` carries a `BuildIdentity` -
 * root seed, `protoHash`, asset graph hash, input schema hash - and is refused the moment any of
 * the four moves. `moba`'s protocol hash moves whenever a replicated component is added, which is
 * ordinary gameplay work, so a `moba` fixture needs the `--update-replay-fixtures` regeneration
 * flag before it can be maintained rather than merely landed. This fixture's identity is a
 * function of nothing but this file.
 *
 * The replicators are hand-written, exactly as `TransformReplicator` and `SnapshotComponents` are
 * and for the same reason: nothing here carries `@Replicated`, so nothing here moves
 * `net-protocol.lock` or the generated-hash fixture.
 *
 * ## Why these fields
 *
 * Between them the two components cover a float column, an int column, a `NetId` column and a
 * `Tick` column, which is four of the seven [FieldKind]s and every backing array
 * `ColumnarFieldStore` lays out except the object one. [Charge] is added and removed while the
 * simulation runs, so the presence bits and the per-component slot count both move - and those
 * are folded into the world hash without being any entity's field, which is precisely the state a
 * bare hash mismatch could never name.
 */

// --- Drifter ---------------------------------------------------------------------------------

/** A body with a position, a heading it integrates, and the tick it last turned on. */
public class Drifter(
    /** `@Net` — the float column the planted divergence perturbs. */
    public var x: Float = 0f,
    /** `@Net`. */
    public var y: Float = 0f,
    /** `@Net` — integrated, never recovered from the velocity: `Vector2.angleDeg` is banned. */
    public var heading: Float = 0f,
    /** `@Net` — spent by turning and refilled by the seeded stream. */
    public var energy: Float = 0f,
    /** `@Sim` — rewinds, never sent. */
    public var lastTurnTick: Tick = Tick.ZERO,
) : Component<Drifter> {
    override fun type(): ComponentType<Drifter> = Drifter

    public companion object : ComponentType<Drifter>()
}

/** The hand-written `Replicator` for [Drifter]. Field indices are the frozen alignment. */
public object DrifterReplicator : Replicator<Drifter> {

    public const val X: Int = 0
    public const val Y: Int = 1
    public const val HEADING: Int = 2
    public const val ENERGY: Int = 3
    public const val LAST_TURN_TICK: Int = 4
    public const val FIELD_COUNT: Int = 5

    override val typeId: ComponentTypeId = ComponentTypeId(1)

    override val fieldNames: List<String> = listOf("x", "y", "heading", "energy", "lastTurnTick")

    override val netMask: FieldMask = MaskOps.of(X, Y, HEADING, ENERGY)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    /** Index-aligned with [fieldNames]; what a `ComponentSchema` lays the columns out from. */
    public val kinds: List<FieldKind> = listOf(
        FieldKind.Float, FieldKind.Float, FieldKind.Float, FieldKind.Float, FieldKind.Tick,
    )

    override fun capture(component: Drifter, store: FieldStore, slot: Int) {
        store.setFloat(slot, X, component.x)
        store.setFloat(slot, Y, component.y)
        store.setFloat(slot, HEADING, component.heading)
        store.setFloat(slot, ENERGY, component.energy)
        store.setTick(slot, LAST_TURN_TICK, component.lastTurnTick)
    }

    /** Floats by raw bits, never `!=` — `FieldStore.fieldEquals` is the semantics that converges. */
    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        for (field in X..ENERGY) {
            if (store.getFloat(slotA, field).toRawBits() != store.getFloat(slotB, field).toRawBits()) {
                mask = MaskOps.set(mask, field)
            }
        }
        if (store.getTick(slotA, LAST_TURN_TICK) != store.getTick(slotB, LAST_TURN_TICK)) {
            mask = MaskOps.set(mask, LAST_TURN_TICK)
        }
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        for (field in X..ENERGY) {
            if (MaskOps.test(mask, field)) out.writeFloat(store.getFloat(slot, field))
        }
        if (MaskOps.test(mask, LAST_TURN_TICK)) {
            out.writeLong(store.getTick(slot, LAST_TURN_TICK).value)
        }
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        for (field in X..ENERGY) {
            if (MaskOps.test(mask, field)) store.setFloat(slot, field, src.readFloat())
        }
        if (MaskOps.test(mask, LAST_TURN_TICK)) {
            store.setTick(slot, LAST_TURN_TICK, Tick(src.readLong()))
        }
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Drifter, mask: FieldMask) {
        if (MaskOps.test(mask, X)) component.x = store.getFloat(slot, X)
        if (MaskOps.test(mask, Y)) component.y = store.getFloat(slot, Y)
        if (MaskOps.test(mask, HEADING)) component.heading = store.getFloat(slot, HEADING)
        if (MaskOps.test(mask, ENERGY)) component.energy = store.getFloat(slot, ENERGY)
        if (MaskOps.test(mask, LAST_TURN_TICK)) {
            component.lastTurnTick = store.getTick(slot, LAST_TURN_TICK)
        }
    }

    override fun getField(component: Drifter, fieldIndex: Int): Any? = when (fieldIndex) {
        X -> component.x
        Y -> component.y
        HEADING -> component.heading
        ENERGY -> component.energy
        LAST_TURN_TICK -> component.lastTurnTick
        else -> throw NoSuchFieldIndexException(TYPE_NAME, fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Drifter, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            X -> component.x = expectFloat(fieldIndex, value)
            Y -> component.y = expectFloat(fieldIndex, value)
            HEADING -> component.heading = expectFloat(fieldIndex, value)
            ENERGY -> component.energy = expectFloat(fieldIndex, value)
            LAST_TURN_TICK -> component.lastTurnTick = value as? Tick
                ?: throw IllegalArgumentException("$TYPE_NAME.lastTurnTick expects a Tick")

            else -> throw NoSuchFieldIndexException(TYPE_NAME, fieldIndex, FIELD_COUNT)
        }
    }

    private fun expectFloat(fieldIndex: Int, value: Any?): Float = value as? Float
        ?: throw IllegalArgumentException("$TYPE_NAME.${fieldNames[fieldIndex]} expects a Float")

    /** The name a divergence report prints, and what `ComponentSchema` is built with. */
    public const val TYPE_NAME: String = "Drifter"
}

// --- Charge ----------------------------------------------------------------------------------

/**
 * A charge one drifter is holding against another.
 *
 * Added and removed while the simulation runs. That is its job: presence bits and per-component
 * slot counts are folded into the world hash and belong to no entity's field list, so a world
 * that diverged only in *who carries a component* has no differing field at all.
 */
public class Charge(
    /** `@Net` — counts down; the component is removed when it reaches zero. */
    public var remaining: Int = 0,
    /** `@Net` — the drifter this charge is aimed at, as a `NetId` and never a Fleks `Entity`. */
    public var target: NetId = NetId.NONE,
) : Component<Charge> {
    override fun type(): ComponentType<Charge> = Charge

    public companion object : ComponentType<Charge>()
}

/** The hand-written `Replicator` for [Charge]. */
public object ChargeReplicator : Replicator<Charge> {

    public const val REMAINING: Int = 0
    public const val TARGET: Int = 1
    public const val FIELD_COUNT: Int = 2

    override val typeId: ComponentTypeId = ComponentTypeId(2)

    override val fieldNames: List<String> = listOf("remaining", "target")

    override val netMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    /** Index-aligned with [fieldNames]. */
    public val kinds: List<FieldKind> = listOf(FieldKind.Int, FieldKind.NetId)

    override fun capture(component: Charge, store: FieldStore, slot: Int) {
        store.setInt(slot, REMAINING, component.remaining)
        store.setNetId(slot, TARGET, component.target)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        if (store.getInt(slotA, REMAINING) != store.getInt(slotB, REMAINING)) {
            mask = MaskOps.set(mask, REMAINING)
        }
        if (store.getNetId(slotA, TARGET) != store.getNetId(slotB, TARGET)) {
            mask = MaskOps.set(mask, TARGET)
        }
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        if (MaskOps.test(mask, REMAINING)) out.writeInt(store.getInt(slot, REMAINING))
        if (MaskOps.test(mask, TARGET)) out.writeInt(store.getNetId(slot, TARGET).raw)
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, REMAINING)) store.setInt(slot, REMAINING, src.readInt())
        if (MaskOps.test(mask, TARGET)) {
            store.setNetId(slot, TARGET, NetId.ofRaw(src.readInt()))
        }
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Charge, mask: FieldMask) {
        if (MaskOps.test(mask, REMAINING)) component.remaining = store.getInt(slot, REMAINING)
        if (MaskOps.test(mask, TARGET)) component.target = store.getNetId(slot, TARGET)
    }

    override fun getField(component: Charge, fieldIndex: Int): Any? = when (fieldIndex) {
        REMAINING -> component.remaining
        TARGET -> component.target
        else -> throw NoSuchFieldIndexException(TYPE_NAME, fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Charge, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            REMAINING -> component.remaining = value as? Int
                ?: throw IllegalArgumentException("$TYPE_NAME.remaining expects an Int")

            TARGET -> component.target = value as? NetId
                ?: throw IllegalArgumentException("$TYPE_NAME.target expects a NetId")

            else -> throw NoSuchFieldIndexException(TYPE_NAME, fieldIndex, FIELD_COUNT)
        }
    }

    /** The name a divergence report prints. */
    public const val TYPE_NAME: String = "Charge"
}

/** The fixture world's component registry, built once per world. */
public object DriftComponents {

    /** [Drifter]'s column layout. */
    public val drifterSchema: ComponentSchema =
        ComponentSchema.of(DrifterReplicator, DrifterReplicator.TYPE_NAME, DrifterReplicator.kinds)

    /** [Charge]'s column layout. */
    public val chargeSchema: ComponentSchema =
        ComponentSchema.of(ChargeReplicator, ChargeReplicator.TYPE_NAME, ChargeReplicator.kinds)

    /**
     * A fresh registry.
     *
     * Fresh per world rather than shared, because `WorldFieldStore.diffInto` refuses two stores
     * built from different registry *objects* and a digest never diffs two live worlds - it
     * writes cells keyed by `ComponentTypeId`, which is registry-independent by construction.
     * Sharing one would work and would hide that property from anyone reading this.
     */
    public fun registry(): ComponentRegistry = ComponentRegistry(
        listOf(
            fleksComponentType(DrifterReplicator, drifterSchema, Drifter) { Drifter() },
            fleksComponentType(ChargeReplicator, chargeSchema, Charge) { Charge() },
        ),
    )
}
