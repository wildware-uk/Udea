package dev.wildware.udea.core.snapshot

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.Vec2
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator

/**
 * Three replicated components, covering every [FieldKind] the store supports.
 *
 * The `Transform` in this module's test fixtures is the *contract's* executable specification
 * and is deliberately not a Fleks component. The snapshot spine needs components that live on
 * real entities in a real world, and it needs the whole type surface — a float, an int, a
 * long, a boolean, a `NetId`, a `Tick` and a reference — because `ColumnarFieldStore` lays each
 * of those out in a different backing array and a store that only ever sees floats would never
 * exercise the layout arithmetic.
 *
 * They also give issue #55's loop gate what it asks for: at least three `@Replicated`
 * components per entity, including movement, a `@Sim`-only field and a `NetId`-referencing
 * field.
 *
 * The `@Net`/`@Sim` split is written by hand in the masks below, exactly as `TransformReplicator`
 * does, because `udea-codegen` must not be the author of the specification it is measured
 * against.
 */

// --- Movement --------------------------------------------------------------------------------

/** Position and velocity, plus a `@Sim` timestamp that must rewind and must never be sent. */
internal class Movement(
    /** `@Net`, lowered to two float fields. Mutated in place, which is why diffing is capture-and-diff. */
    val position: Vec2 = Vec2(),
    /** `@Net`, lowered to two float fields. */
    val velocity: Vec2 = Vec2(),
    /** `@Sim` — rewinds, never reaches a client. */
    var lastGroundedTick: Tick = Tick.ZERO,
) : Component<Movement> {
    override fun type(): ComponentType<Movement> = Movement

    companion object : ComponentType<Movement>()
}

internal object MovementReplicator : Replicator<Movement> {

    const val POSITION_X = 0
    const val POSITION_Y = 1
    const val VELOCITY_X = 2
    const val VELOCITY_Y = 3
    const val LAST_GROUNDED_TICK = 4
    const val FIELD_COUNT = 5

    override val typeId: ComponentTypeId = ComponentTypeId(1)

    override val fieldNames: List<String> =
        listOf("position.x", "position.y", "velocity.x", "velocity.y", "lastGroundedTick")

    override val netMask: FieldMask = MaskOps.of(POSITION_X, POSITION_Y, VELOCITY_X, VELOCITY_Y)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    val kinds: List<FieldKind> = listOf(
        FieldKind.Float, FieldKind.Float, FieldKind.Float, FieldKind.Float, FieldKind.Tick,
    )

    override fun capture(component: Movement, store: FieldStore, slot: Int) {
        store.setFloat(slot, POSITION_X, component.position.x)
        store.setFloat(slot, POSITION_Y, component.position.y)
        store.setFloat(slot, VELOCITY_X, component.velocity.x)
        store.setFloat(slot, VELOCITY_Y, component.velocity.y)
        store.setTick(slot, LAST_GROUNDED_TICK, component.lastGroundedTick)
    }

    /** Floats compared by raw bits, never `!=` — see `FieldStore.fieldEquals`. */
    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        for (field in POSITION_X..VELOCITY_Y) {
            if (store.getFloat(slotA, field).toRawBits() != store.getFloat(slotB, field).toRawBits()) {
                mask = MaskOps.set(mask, field)
            }
        }
        if (store.getTick(slotA, LAST_GROUNDED_TICK) != store.getTick(slotB, LAST_GROUNDED_TICK)) {
            mask = MaskOps.set(mask, LAST_GROUNDED_TICK)
        }
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        for (field in POSITION_X..VELOCITY_Y) {
            if (MaskOps.test(mask, field)) out.writeFloat(store.getFloat(slot, field))
        }
        if (MaskOps.test(mask, LAST_GROUNDED_TICK)) {
            out.writeLong(store.getTick(slot, LAST_GROUNDED_TICK).value)
        }
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        for (field in POSITION_X..VELOCITY_Y) {
            if (MaskOps.test(mask, field)) store.setFloat(slot, field, src.readFloat())
        }
        if (MaskOps.test(mask, LAST_GROUNDED_TICK)) {
            store.setTick(slot, LAST_GROUNDED_TICK, Tick(src.readLong()))
        }
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Movement, mask: FieldMask) {
        if (MaskOps.test(mask, POSITION_X)) component.position.x = store.getFloat(slot, POSITION_X)
        if (MaskOps.test(mask, POSITION_Y)) component.position.y = store.getFloat(slot, POSITION_Y)
        if (MaskOps.test(mask, VELOCITY_X)) component.velocity.x = store.getFloat(slot, VELOCITY_X)
        if (MaskOps.test(mask, VELOCITY_Y)) component.velocity.y = store.getFloat(slot, VELOCITY_Y)
        if (MaskOps.test(mask, LAST_GROUNDED_TICK)) {
            component.lastGroundedTick = store.getTick(slot, LAST_GROUNDED_TICK)
        }
    }

    override fun getField(component: Movement, fieldIndex: Int): Any? = when (fieldIndex) {
        POSITION_X -> component.position.x
        POSITION_Y -> component.position.y
        VELOCITY_X -> component.velocity.x
        VELOCITY_Y -> component.velocity.y
        LAST_GROUNDED_TICK -> component.lastGroundedTick
        else -> throw NoSuchFieldIndexException("Movement", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Movement, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            POSITION_X -> component.position.x = value as Float
            POSITION_Y -> component.position.y = value as Float
            VELOCITY_X -> component.velocity.x = value as Float
            VELOCITY_Y -> component.velocity.y = value as Float
            LAST_GROUNDED_TICK -> component.lastGroundedTick = value as Tick
            else -> throw NoSuchFieldIndexException("Movement", fieldIndex, FIELD_COUNT)
        }
    }
}

// --- Vitals ----------------------------------------------------------------------------------

/** Health and its bookkeeping: a float, an int, a boolean, a long and a tick. */
internal class Vitals(
    var health: Float = 100f,
    var shieldCharges: Int = 0,
    var invulnerable: Boolean = false,
    /** `@Sim` — a statistic the client never needs. */
    var damageDealt: Long = 0L,
    /** `@Sim` — a respawn timer, exactly the "jungle timer" spec 3.1 says must rewind. */
    var respawnTick: Tick = Tick.ZERO,
) : Component<Vitals> {
    override fun type(): ComponentType<Vitals> = Vitals

    companion object : ComponentType<Vitals>()
}

internal object VitalsReplicator : Replicator<Vitals> {

    const val HEALTH = 0
    const val SHIELD_CHARGES = 1
    const val INVULNERABLE = 2
    const val DAMAGE_DEALT = 3
    const val RESPAWN_TICK = 4
    const val FIELD_COUNT = 5

    override val typeId: ComponentTypeId = ComponentTypeId(2)

    override val fieldNames: List<String> =
        listOf("health", "shieldCharges", "invulnerable", "damageDealt", "respawnTick")

    override val netMask: FieldMask = MaskOps.of(HEALTH, SHIELD_CHARGES, INVULNERABLE)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    val kinds: List<FieldKind> = listOf(
        FieldKind.Float, FieldKind.Int, FieldKind.Bool, FieldKind.Long, FieldKind.Tick,
    )

    override fun capture(component: Vitals, store: FieldStore, slot: Int) {
        store.setFloat(slot, HEALTH, component.health)
        store.setInt(slot, SHIELD_CHARGES, component.shieldCharges)
        store.setBoolean(slot, INVULNERABLE, component.invulnerable)
        store.setLong(slot, DAMAGE_DEALT, component.damageDealt)
        store.setTick(slot, RESPAWN_TICK, component.respawnTick)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        if (store.getFloat(slotA, HEALTH).toRawBits() != store.getFloat(slotB, HEALTH).toRawBits()) {
            mask = MaskOps.set(mask, HEALTH)
        }
        if (store.getInt(slotA, SHIELD_CHARGES) != store.getInt(slotB, SHIELD_CHARGES)) {
            mask = MaskOps.set(mask, SHIELD_CHARGES)
        }
        if (store.getBoolean(slotA, INVULNERABLE) != store.getBoolean(slotB, INVULNERABLE)) {
            mask = MaskOps.set(mask, INVULNERABLE)
        }
        if (store.getLong(slotA, DAMAGE_DEALT) != store.getLong(slotB, DAMAGE_DEALT)) {
            mask = MaskOps.set(mask, DAMAGE_DEALT)
        }
        if (store.getTick(slotA, RESPAWN_TICK) != store.getTick(slotB, RESPAWN_TICK)) {
            mask = MaskOps.set(mask, RESPAWN_TICK)
        }
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        if (MaskOps.test(mask, HEALTH)) out.writeFloat(store.getFloat(slot, HEALTH))
        if (MaskOps.test(mask, SHIELD_CHARGES)) out.writeInt(store.getInt(slot, SHIELD_CHARGES))
        if (MaskOps.test(mask, INVULNERABLE)) out.writeBoolean(store.getBoolean(slot, INVULNERABLE))
        if (MaskOps.test(mask, DAMAGE_DEALT)) out.writeLong(store.getLong(slot, DAMAGE_DEALT))
        if (MaskOps.test(mask, RESPAWN_TICK)) out.writeLong(store.getTick(slot, RESPAWN_TICK).value)
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, HEALTH)) store.setFloat(slot, HEALTH, src.readFloat())
        if (MaskOps.test(mask, SHIELD_CHARGES)) store.setInt(slot, SHIELD_CHARGES, src.readInt())
        if (MaskOps.test(mask, INVULNERABLE)) store.setBoolean(slot, INVULNERABLE, src.readBoolean())
        if (MaskOps.test(mask, DAMAGE_DEALT)) store.setLong(slot, DAMAGE_DEALT, src.readLong())
        if (MaskOps.test(mask, RESPAWN_TICK)) store.setTick(slot, RESPAWN_TICK, Tick(src.readLong()))
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Vitals, mask: FieldMask) {
        if (MaskOps.test(mask, HEALTH)) component.health = store.getFloat(slot, HEALTH)
        if (MaskOps.test(mask, SHIELD_CHARGES)) component.shieldCharges = store.getInt(slot, SHIELD_CHARGES)
        if (MaskOps.test(mask, INVULNERABLE)) component.invulnerable = store.getBoolean(slot, INVULNERABLE)
        if (MaskOps.test(mask, DAMAGE_DEALT)) component.damageDealt = store.getLong(slot, DAMAGE_DEALT)
        if (MaskOps.test(mask, RESPAWN_TICK)) component.respawnTick = store.getTick(slot, RESPAWN_TICK)
    }

    override fun getField(component: Vitals, fieldIndex: Int): Any? = when (fieldIndex) {
        HEALTH -> component.health
        SHIELD_CHARGES -> component.shieldCharges
        INVULNERABLE -> component.invulnerable
        DAMAGE_DEALT -> component.damageDealt
        RESPAWN_TICK -> component.respawnTick
        else -> throw NoSuchFieldIndexException("Vitals", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Vitals, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            HEALTH -> component.health = value as Float
            SHIELD_CHARGES -> component.shieldCharges = value as Int
            INVULNERABLE -> component.invulnerable = value as Boolean
            DAMAGE_DEALT -> component.damageDealt = value as Long
            RESPAWN_TICK -> component.respawnTick = value as Tick
            else -> throw NoSuchFieldIndexException("Vitals", fieldIndex, FIELD_COUNT)
        }
    }
}

// --- Link ------------------------------------------------------------------------------------

/**
 * Entity references and one reference-typed field.
 *
 * `NetId` fields exist because an entity reference is the field type most likely to grow a
 * special case, and spec 5 says there must not be one. [squad] exercises the object column and
 * is an interned `String`: deeply immutable with a `hashCode` specified by the platform, which
 * is what `FieldKind.Object` requires of anything the determinism hash folds.
 */
internal class Link(
    /** `@Net` — the current target. */
    var target: NetId = NetId.NONE,
    /** `@Sim` — who hit us last. */
    var lastAttacker: NetId = NetId.NONE,
    /** `@Sim` — an interned squad id. */
    var squad: String? = null,
) : Component<Link> {
    override fun type(): ComponentType<Link> = Link

    companion object : ComponentType<Link>()
}

internal object LinkReplicator : Replicator<Link> {

    const val TARGET = 0
    const val LAST_ATTACKER = 1
    const val SQUAD = 2
    const val FIELD_COUNT = 3

    override val typeId: ComponentTypeId = ComponentTypeId(3)

    override val fieldNames: List<String> = listOf("target", "lastAttacker", "squad")

    override val netMask: FieldMask = MaskOps.of(TARGET)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    val kinds: List<FieldKind> = listOf(FieldKind.NetId, FieldKind.NetId, FieldKind.Object)

    override fun capture(component: Link, store: FieldStore, slot: Int) {
        store.setNetId(slot, TARGET, component.target)
        store.setNetId(slot, LAST_ATTACKER, component.lastAttacker)
        store.setObject(slot, SQUAD, component.squad)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        if (store.getNetId(slotA, TARGET) != store.getNetId(slotB, TARGET)) {
            mask = MaskOps.set(mask, TARGET)
        }
        if (store.getNetId(slotA, LAST_ATTACKER) != store.getNetId(slotB, LAST_ATTACKER)) {
            mask = MaskOps.set(mask, LAST_ATTACKER)
        }
        if (store.getObject(slotA, SQUAD) != store.getObject(slotB, SQUAD)) {
            mask = MaskOps.set(mask, SQUAD)
        }
        return mask
    }

    /** `squad` is `@Sim`, so it never appears on the wire and [write] never has to encode it. */
    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        if (MaskOps.test(mask, TARGET)) out.writeInt(store.getNetId(slot, TARGET).raw)
        if (MaskOps.test(mask, LAST_ATTACKER)) out.writeInt(store.getNetId(slot, LAST_ATTACKER).raw)
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, TARGET)) store.setNetId(slot, TARGET, NetId.ofRaw(src.readInt()))
        if (MaskOps.test(mask, LAST_ATTACKER)) {
            store.setNetId(slot, LAST_ATTACKER, NetId.ofRaw(src.readInt()))
        }
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Link, mask: FieldMask) {
        if (MaskOps.test(mask, TARGET)) component.target = store.getNetId(slot, TARGET)
        if (MaskOps.test(mask, LAST_ATTACKER)) {
            component.lastAttacker = store.getNetId(slot, LAST_ATTACKER)
        }
        if (MaskOps.test(mask, SQUAD)) component.squad = store.getObject(slot, SQUAD) as String?
    }

    override fun getField(component: Link, fieldIndex: Int): Any? = when (fieldIndex) {
        TARGET -> component.target
        LAST_ATTACKER -> component.lastAttacker
        SQUAD -> component.squad
        else -> throw NoSuchFieldIndexException("Link", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Link, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            TARGET -> component.target = value as NetId
            LAST_ATTACKER -> component.lastAttacker = value as NetId
            SQUAD -> component.squad = value as String?
            else -> throw NoSuchFieldIndexException("Link", fieldIndex, FIELD_COUNT)
        }
    }
}

/** The schemas and the registry every snapshot test shares. */
internal object TestComponents {

    val movementSchema: ComponentSchema =
        ComponentSchema.of(MovementReplicator, "Movement", MovementReplicator.kinds)

    val vitalsSchema: ComponentSchema =
        ComponentSchema.of(VitalsReplicator, "Vitals", VitalsReplicator.kinds)

    val linkSchema: ComponentSchema = ComponentSchema.of(LinkReplicator, "Link", LinkReplicator.kinds)

    /**
     * Deliberately registered out of id order, so the registry's canonical sort is exercised
     * by every test that uses it rather than only by the one that asserts it.
     */
    fun registry(): ComponentRegistry = ComponentRegistry(
        listOf(
            fleksComponentType(LinkReplicator, linkSchema, Link) { Link() },
            fleksComponentType(MovementReplicator, movementSchema, Movement) { Movement() },
            fleksComponentType(VitalsReplicator, vitalsSchema, Vitals) { Vitals() },
        ),
    )
}
