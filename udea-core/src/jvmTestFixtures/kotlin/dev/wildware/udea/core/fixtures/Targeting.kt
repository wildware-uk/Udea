package dev.wildware.udea.core.fixtures

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
 * A component whose fields reference other entities.
 *
 * In the real tree this carries `@Net var target: NetId` and `@Sim var lastAttacker: NetId`.
 * It exists because entity references are the field type most likely to grow a special case,
 * and spec 5 says there must not be one: a [NetId] is a primitive field type as far as the
 * [FieldStore] and every [Replicator] are concerned, so an entity-referencing field
 * round-trips through capture, restore and delta write exactly like a float does.
 *
 * What must *not* appear anywhere here is a Fleks `Entity`. That is one world's slot index,
 * and `common/network/packets.kt` shipped one across the wire in `EntityCreate.entity`.
 */
public class Targeting(
    /** `@Net` — the current target, replicated and snapshotted. */
    public var target: NetId = NetId.NONE,
    /** `@Sim` — who hit us last. Snapshotted for rewind; never sent to a client. */
    public var lastAttacker: NetId = NetId.NONE,
) {
    override fun toString(): String = "Targeting(target=$target, lastAttacker=$lastAttacker)"
}

/** Hand-written `Replicator` for [Targeting]. See [TransformReplicator] for the contract. */
public object TargetingReplicator : Replicator<Targeting> {

    public const val FIELD_TARGET: Int = 0
    public const val FIELD_LAST_ATTACKER: Int = 1
    public const val FIELD_COUNT: Int = 2

    override val typeId: ComponentTypeId = ComponentTypeId(2)

    override val fieldNames: List<String> = listOf("target", "lastAttacker")

    override val netMask: FieldMask = MaskOps.of(FIELD_TARGET)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Targeting, store: FieldStore, slot: Int) {
        store.setNetId(slot, FIELD_TARGET, component.target)
        store.setNetId(slot, FIELD_LAST_ATTACKER, component.lastAttacker)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        if (store.getNetId(slotA, FIELD_TARGET) != store.getNetId(slotB, FIELD_TARGET)) {
            mask = MaskOps.set(mask, FIELD_TARGET)
        }
        if (store.getNetId(slotA, FIELD_LAST_ATTACKER) != store.getNetId(slotB, FIELD_LAST_ATTACKER)) {
            mask = MaskOps.set(mask, FIELD_LAST_ATTACKER)
        }
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        if (MaskOps.test(mask, FIELD_TARGET)) out.writeInt(store.getNetId(slot, FIELD_TARGET).raw)
        if (MaskOps.test(mask, FIELD_LAST_ATTACKER)) {
            out.writeInt(store.getNetId(slot, FIELD_LAST_ATTACKER).raw)
        }
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, FIELD_TARGET)) {
            store.setNetId(slot, FIELD_TARGET, NetId.ofRaw(src.readInt()))
        }
        if (MaskOps.test(mask, FIELD_LAST_ATTACKER)) {
            store.setNetId(slot, FIELD_LAST_ATTACKER, NetId.ofRaw(src.readInt()))
        }
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Targeting, mask: FieldMask) {
        if (MaskOps.test(mask, FIELD_TARGET)) {
            component.target = store.getNetId(slot, FIELD_TARGET)
        }
        if (MaskOps.test(mask, FIELD_LAST_ATTACKER)) {
            component.lastAttacker = store.getNetId(slot, FIELD_LAST_ATTACKER)
        }
    }

    override fun getField(component: Targeting, fieldIndex: Int): Any? = when (fieldIndex) {
        FIELD_TARGET -> component.target
        FIELD_LAST_ATTACKER -> component.lastAttacker
        else -> throw NoSuchFieldIndexException(TYPE_NAME, fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Targeting, fieldIndex: Int, value: Any?) {
        val netId = value as? NetId
        when (fieldIndex) {
            FIELD_TARGET, FIELD_LAST_ATTACKER -> requireNotNull(netId) {
                "$TYPE_NAME.${fieldNames[fieldIndex]} expects a NetId, got $value"
            }

            else -> throw NoSuchFieldIndexException(TYPE_NAME, fieldIndex, FIELD_COUNT)
        }
        if (fieldIndex == FIELD_TARGET) component.target = netId else component.lastAttacker = netId
    }

    private const val TYPE_NAME: String = "Targeting"
}
