package dev.wildware.udea.gas

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
import dev.wildware.udea.core.snapshot.StableHash

/**
 * An immutable copy of one entity's activation records, with a content hash.
 *
 * ## Why these codecs are hand-written
 *
 * `udea-codegen` lowers a `@Replicated` component one property at a time and refuses anything
 * that is not a scalar, an enum, a `NetId`, a `Tick` or a one-level-deep value type
 * (`FieldLowering`). [Abilities] is an array of activation records and [GameplayEffects] is ten
 * parallel primitive columns, so neither has a generated codec and neither will have one without
 * a variable-length column kind in `ColumnarFieldStore`. Until that exists the established
 * workaround in this module is [AttributesReplicator]'s: **one**
 * [dev.wildware.udea.core.snapshot.FieldKind.Object] field holding an immutable, content-hashed
 * vector. These two follow it exactly, for the same reason and at the same cost.
 *
 * ## The cost, stated
 *
 * One vector allocation per component per capture, and a copy of every column into it. At the
 * default 20Hz cadence that is two allocations per dressed unit per capture. It is real, it is on
 * the capture path, and it is why
 * [dev.wildware.udea.core.snapshot.SnapshotBudgets.CAPTURE_ALLOCATED_BYTES] is measured against
 * `udea-core`'s scalar fixtures rather than against a MOBA unit. The fix is a variable-length
 * column kind, which is `udea-core`'s to add; this is what makes a rewind correct meanwhile, and
 * a rewind that resurrects half a unit is not a performance trade.
 *
 * ## What is deliberately not carried
 *
 * [HandleAllocator] is a world service and not a component, so it is outside every snapshot.
 * Handles are monotonic and never recycled, so a restored world re-issues *higher* handles than
 * the run it rewound over rather than colliding with the ones it just restored: the effect list
 * stays sorted and searchable. It does mean a replay from a restored keyframe numbers its handles
 * differently from the original run, which a bit-exact determinism gate over handles would see.
 * Restoring it needs a snapshot slot for world services, which no component codec can reach.
 */
public class AbilityVector private constructor(
    /** How many slots the captured component had. */
    public val slotCount: Int,
    /** The captured `Abilities.instanceCounter`. */
    public val instanceCounter: Int,
    private val ints: IntArray,
    private val longs: LongArray,
    private val floats: FloatArray,
) : StableHash {

    /** Raw-bit content equality, matching `FieldStore.fieldEquals`. */
    override fun equals(other: Any?): Boolean =
        other is AbilityVector &&
            other.slotCount == slotCount &&
            other.instanceCounter == instanceCounter &&
            other.ints.contentEquals(ints) &&
            other.longs.contentEquals(longs) &&
            floatsEqual(other.floats)

    /**
     * Content hash over raw bits, stable across processes.
     *
     * Raw bits for the floats rather than `FloatArray.contentHashCode`, so `-0.0` and `0.0` hash
     * apart exactly as `FieldStore.fieldEquals` compares them apart.
     */
    override fun hashCode(): Int {
        var hash = 31 * slotCount + instanceCounter
        for (value in ints) hash = 31 * hash + value
        for (value in longs) hash = 31 * hash + (value xor (value ushr 32)).toInt()
        for (value in floats) hash = 31 * hash + value.toRawBits()
        return hash
    }

    private fun floatsEqual(other: FloatArray): Boolean {
        if (other.size != floats.size) return false
        var index = 0
        while (index < floats.size) {
            if (floats[index].toRawBits() != other[index].toRawBits()) return false
            index++
        }
        return true
    }

    override fun toString(): String = "AbilityVector($slotCount slots)"

    internal fun intAt(index: Int): Int = ints[index]

    internal fun longAt(index: Int): Long = longs[index]

    internal fun floatAt(index: Int): Float = floats[index]

    internal fun writeTo(out: BitWriter) {
        out.writeInt(slotCount)
        out.writeInt(instanceCounter)
        for (value in ints) out.writeInt(value)
        for (value in longs) out.writeLong(value)
        for (value in floats) out.writeFloat(value)
    }

    public companion object {

        /** Ints stored per slot. [AbilitiesReplicator] names what each one is. */
        internal const val INTS_PER_SLOT: Int =
            8 + AbilityInstance.MAX_MULTI_TARGETS + AbilityInstance.SCRATCH_SLOTS

        /** Longs per slot: the activation tick. */
        internal const val LONGS_PER_SLOT: Int = 1

        /** Floats per slot: target x, target y, and the four scratch floats. */
        internal const val FLOATS_PER_SLOT: Int = 2 + AbilityInstance.SCRATCH_SLOTS

        internal const val ABILITY_INDEX: Int = 0
        internal const val INSTANCE_ID: Int = 1
        internal const val PHASE: Int = 2
        internal const val COOLDOWN_HANDLE: Int = 3
        internal const val PREDICTION_KEY: Int = 4
        internal const val TARGET_KIND: Int = 5
        internal const val TARGET_ID: Int = 6
        internal const val MULTI_COUNT: Int = 7
        internal const val MULTI_FIRST: Int = 8
        internal const val SCRATCH_INT_FIRST: Int = MULTI_FIRST + AbilityInstance.MAX_MULTI_TARGETS

        internal const val TARGET_X: Int = 0
        internal const val TARGET_Y: Int = 1
        internal const val SCRATCH_FLOAT_FIRST: Int = 2

        /** A vector holding a copy of every activation record in [component]. */
        public fun of(component: Abilities): AbilityVector {
            val slots = component.slotCount
            val ints = IntArray(slots * INTS_PER_SLOT)
            val longs = LongArray(slots * LONGS_PER_SLOT)
            val floats = FloatArray(slots * FLOATS_PER_SLOT)
            var slot = 0
            while (slot < slots) {
                val instance = component.instanceAt(slot)
                val i = slot * INTS_PER_SLOT
                ints[i + ABILITY_INDEX] = instance.abilityIndex
                ints[i + INSTANCE_ID] = instance.instanceId
                ints[i + PHASE] = instance.phase.ordinal
                ints[i + COOLDOWN_HANDLE] = instance.cooldownHandle.raw
                ints[i + PREDICTION_KEY] = instance.predictionKey
                ints[i + TARGET_KIND] = instance.targetKind.ordinal
                ints[i + TARGET_ID] = instance.targetId.raw
                ints[i + MULTI_COUNT] = instance.multiTargetCount
                var target = 0
                while (target < AbilityInstance.MAX_MULTI_TARGETS) {
                    ints[i + MULTI_FIRST + target] = if (target < instance.multiTargetCount) {
                        instance.multiTargetAt(target).raw
                    } else {
                        NetId.NONE.raw
                    }
                    target++
                }
                var scratch = 0
                while (scratch < AbilityInstance.SCRATCH_SLOTS) {
                    ints[i + SCRATCH_INT_FIRST + scratch] = instance.scratchInts[scratch]
                    scratch++
                }
                longs[slot * LONGS_PER_SLOT] = instance.activatedTick.value
                val f = slot * FLOATS_PER_SLOT
                floats[f + TARGET_X] = instance.targetX
                floats[f + TARGET_Y] = instance.targetY
                scratch = 0
                while (scratch < AbilityInstance.SCRATCH_SLOTS) {
                    floats[f + SCRATCH_FLOAT_FIRST + scratch] = instance.scratchFloats[scratch]
                    scratch++
                }
                slot++
            }
            return AbilityVector(slots, component.instanceCounter, ints, longs, floats)
        }

        /** Reads a vector [writeTo] wrote. */
        internal fun readFrom(src: BitReader): AbilityVector {
            val slots = src.readInt()
            require(slots > 0) { "an AbilityVector names $slots slots; a component has at least one" }
            val counter = src.readInt()
            val ints = IntArray(slots * INTS_PER_SLOT) { src.readInt() }
            val longs = LongArray(slots * LONGS_PER_SLOT) { src.readLong() }
            val floats = FloatArray(slots * FLOATS_PER_SLOT) { src.readFloat() }
            return AbilityVector(slots, counter, ints, longs, floats)
        }
    }
}

/**
 * Replicates every activation record on an entity through **one** field.
 *
 * ## Why one field and not one per slot
 *
 * An [AbilityInstance] is twenty-seven values. Six slots is one hundred and sixty-two lowered
 * fields against a sixty-four-bit `FieldMask`: it does not fit, and no amount of splitting the
 * component turns a variable-length array of records into a fixed set of named scalars. The
 * dense-array mitigation spec 7 names for [Attributes] is the same answer here.
 *
 * ## What a restore actually puts back
 *
 * The phase, the activation tick, the target, the cooldown handle and both scratch banks, which
 * together are the whole of an activation. That is the difference between a rewind that undoes a
 * cast and the behaviour a play agent measured on `moba`, where fourteen activations stayed in
 * flight across a three-hundred-tick rewind because nothing captured them at all.
 */
public class AbilitiesReplicator(
    override val typeId: ComponentTypeId = ComponentTypeId(DEFAULT_TYPE_ID),
) : Replicator<Abilities> {

    /** One name, because there is one field. */
    override val fieldNames: List<String> = listOf("instances")

    /**
     * Empty: an activation is simulation state and never reaches a client through this codec.
     *
     * A client predicts its own casts and is told about other people's through cues, so putting
     * every entity's whole ability bank on the wire whenever one number in it moves would spend
     * the packet budget on state the receiver does not read. [allMask] still carries it, which is
     * what makes it snapshot state: the `@Sim` half of the two-mask rule.
     */
    override val netMask: FieldMask = MaskOps.EMPTY

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Abilities, store: FieldStore, slot: Int) {
        store.setObject(slot, INSTANCES, AbilityVector.of(component))
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask =
        if (store.fieldEquals(slotA, slotB, INSTANCES)) MaskOps.EMPTY else MaskOps.single(INSTANCES)

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        vectorAt(store, slot).writeTo(out)
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, INSTANCES)) {
            store.setObject(slot, INSTANCES, AbilityVector.readFrom(src))
        }
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Abilities, mask: FieldMask) {
        if (!MaskOps.test(mask, INSTANCES)) return
        writeOnto(vectorAt(store, slot), component)
    }

    override fun getField(component: Abilities, fieldIndex: Int): Any? = when (fieldIndex) {
        INSTANCES -> AbilityVector.of(component)
        else -> throw NoSuchFieldIndexException("Abilities", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Abilities, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            INSTANCES -> writeOnto(value as AbilityVector, component)
            else -> throw NoSuchFieldIndexException("Abilities", fieldIndex, FIELD_COUNT)
        }
    }

    private fun writeOnto(vector: AbilityVector, component: Abilities) {
        require(vector.slotCount == component.slotCount) {
            "a snapshot of ${vector.slotCount} ability slot(s) cannot be applied to a component " +
                "with ${component.slotCount}; the blueprint that spawns this entity changed its " +
                "slot count since the snapshot was taken"
        }
        var slot = 0
        while (slot < component.slotCount) {
            applySlot(vector, component.instanceAt(slot), slot)
            slot++
        }
        component.restoreInstanceCounter(vector.instanceCounter)
    }

    private fun applySlot(vector: AbilityVector, instance: AbilityInstance, slot: Int) {
        val i = slot * AbilityVector.INTS_PER_SLOT
        val f = slot * AbilityVector.FLOATS_PER_SLOT
        instance.abilityIndex = vector.intAt(i + AbilityVector.ABILITY_INDEX)
        instance.instanceId = vector.intAt(i + AbilityVector.INSTANCE_ID)
        instance.phase = PHASES[vector.intAt(i + AbilityVector.PHASE)]
        instance.cooldownHandle = EffectHandle(vector.intAt(i + AbilityVector.COOLDOWN_HANDLE))
        instance.predictionKey = vector.intAt(i + AbilityVector.PREDICTION_KEY)
        instance.activatedTick = Tick(vector.longAt(slot * AbilityVector.LONGS_PER_SLOT))

        // The multi-target list is private to `AbilityInstance`, so it is rebuilt through the same
        // public writer gameplay uses rather than by reaching past it. `addMultiTarget` sets the
        // target kind as a side effect, which is why the captured kind is written afterwards.
        instance.clearTarget()
        val multiCount = vector.intAt(i + AbilityVector.MULTI_COUNT)
        var target = 0
        while (target < multiCount) {
            instance.addMultiTarget(NetId.ofRaw(vector.intAt(i + AbilityVector.MULTI_FIRST + target)))
            target++
        }
        instance.targetId = NetId.ofRaw(vector.intAt(i + AbilityVector.TARGET_ID))
        instance.targetX = vector.floatAt(f + AbilityVector.TARGET_X)
        instance.targetY = vector.floatAt(f + AbilityVector.TARGET_Y)
        instance.targetKind = TARGET_KINDS[vector.intAt(i + AbilityVector.TARGET_KIND)]

        var scratch = 0
        while (scratch < AbilityInstance.SCRATCH_SLOTS) {
            instance.scratchInts[scratch] = vector.intAt(i + AbilityVector.SCRATCH_INT_FIRST + scratch)
            instance.scratchFloats[scratch] =
                vector.floatAt(f + AbilityVector.SCRATCH_FLOAT_FIRST + scratch)
            scratch++
        }
    }

    private fun vectorAt(store: FieldStore, slot: Int): AbilityVector =
        store.getObject(slot, INSTANCES) as? AbilityVector
            ?: error("slot $slot holds no captured ability vector; capture before diffing or writing")

    public companion object {
        /** The one lowered field: every activation record on the entity. */
        public const val INSTANCES: Int = 0

        /** One field, which is the whole point of this replicator. */
        public const val FIELD_COUNT: Int = 1

        /** See [AttributesReplicator.DEFAULT_TYPE_ID]: a placeholder for a single-module build. */
        public const val DEFAULT_TYPE_ID: Int = 65

        private val PHASES: Array<AbilityPhase> = AbilityPhase.entries.toTypedArray()
        private val TARGET_KINDS: Array<AbilityTargetKind> = AbilityTargetKind.entries.toTypedArray()
    }
}

/**
 * An immutable copy of one entity's applied-effect list, with a content hash.
 *
 * The same shape as [AttributeVector] and for the same reason: [FieldStore.setObject] refuses a
 * value whose `hashCode` is an identity hash, because `WorldHasher` folds an object column's hash
 * straight into the determinism hash.
 */
public class EffectVector private constructor(
    /** How many effects were applied. */
    public val count: Int,
    private val ints: IntArray,
    private val longs: LongArray,
    private val floats: FloatArray,
) : StableHash {

    override fun equals(other: Any?): Boolean =
        other is EffectVector &&
            other.count == count &&
            other.ints.contentEquals(ints) &&
            other.longs.contentEquals(longs) &&
            floatsEqual(other.floats)

    override fun hashCode(): Int {
        var hash = count
        for (value in ints) hash = 31 * hash + value
        for (value in longs) hash = 31 * hash + (value xor (value ushr 32)).toInt()
        for (value in floats) hash = 31 * hash + value.toRawBits()
        return hash
    }

    private fun floatsEqual(other: FloatArray): Boolean {
        if (other.size != floats.size) return false
        var index = 0
        while (index < floats.size) {
            if (floats[index].toRawBits() != other[index].toRawBits()) return false
            index++
        }
        return true
    }

    override fun toString(): String = "EffectVector($count applied)"

    internal fun intAt(index: Int): Int = ints[index]

    internal fun longAt(index: Int): Long = longs[index]

    internal fun floatAt(index: Int): Float = floats[index]

    internal fun writeTo(out: BitWriter) {
        out.writeInt(count)
        for (value in ints) out.writeInt(value)
        for (value in longs) out.writeLong(value)
        for (value in floats) out.writeFloat(value)
    }

    public companion object {

        /** Ints per applied effect: handle, definition, period, stacks, source, and four tags. */
        internal const val INTS_PER_EFFECT: Int = 5 + GameplayEffects.SET_BY_CALLER_SLOTS

        /** Longs per applied effect: applied tick, duration, next periodic tick. */
        internal const val LONGS_PER_EFFECT: Int = 3

        /** Floats per applied effect: one set-by-caller magnitude per tag slot. */
        internal const val FLOATS_PER_EFFECT: Int = GameplayEffects.SET_BY_CALLER_SLOTS

        internal const val HANDLE: Int = 0
        internal const val DEF_INDEX: Int = 1
        internal const val PERIOD_TICKS: Int = 2
        internal const val STACKS: Int = 3
        internal const val SOURCE: Int = 4
        internal const val TAG_FIRST: Int = 5

        internal const val APPLIED_TICK: Int = 0
        internal const val DURATION: Int = 1
        internal const val NEXT_PERIOD: Int = 2

        /** A vector holding a copy of every applied effect in [component]. */
        public fun of(component: GameplayEffects): EffectVector {
            val count = component.count
            val ints = IntArray(count * INTS_PER_EFFECT)
            val longs = LongArray(count * LONGS_PER_EFFECT)
            val floats = FloatArray(count * FLOATS_PER_EFFECT)
            var effect = 0
            while (effect < count) {
                val i = effect * INTS_PER_EFFECT
                ints[i + HANDLE] = component.handleAt(effect).raw
                ints[i + DEF_INDEX] = component.defIndexAt(effect)
                ints[i + PERIOD_TICKS] = component.periodTicksAt(effect)
                ints[i + STACKS] = component.stacksAt(effect)
                ints[i + SOURCE] = component.sourceAt(effect).raw
                val l = effect * LONGS_PER_EFFECT
                longs[l + APPLIED_TICK] = component.appliedTickAt(effect).value
                longs[l + DURATION] = component.durationTicksAt(effect)
                longs[l + NEXT_PERIOD] = component.nextPeriodTickAt(effect).value
                val f = effect * FLOATS_PER_EFFECT
                var offset = 0
                while (offset < GameplayEffects.SET_BY_CALLER_SLOTS) {
                    ints[i + TAG_FIRST + offset] = component.magnitudeTagAt(effect, offset).id
                    floats[f + offset] = component.magnitudeValueAt(effect, offset)
                    offset++
                }
                effect++
            }
            return EffectVector(count, ints, longs, floats)
        }

        /** Reads a vector [writeTo] wrote. */
        internal fun readFrom(src: BitReader): EffectVector {
            val count = src.readInt()
            require(count >= 0) { "an EffectVector cannot hold $count effects" }
            val ints = IntArray(count * INTS_PER_EFFECT) { src.readInt() }
            val longs = LongArray(count * LONGS_PER_EFFECT) { src.readLong() }
            val floats = FloatArray(count * FLOATS_PER_EFFECT) { src.readFloat() }
            return EffectVector(count, ints, longs, floats)
        }
    }
}

/**
 * Replicates one entity's whole applied-effect list through **one** field.
 *
 * Same one-object-field mitigation as [AbilitiesReplicator], and necessary for the same reason:
 * the list is variable length, so there is no fixed set of named scalars to lower it to.
 *
 * ## Why a restore rebuilds the list rather than writing into it
 *
 * [GameplayEffects] keeps its columns private and keeps them **sorted by handle**, which is what
 * makes `indexOfHandle` a binary search. Writing those columns from here would put the class's
 * central invariant in a second file. So a restore clears the list and re-applies every captured
 * effect through `add`, in captured order, which is ascending handle order because capture reads
 * the sorted list. `add`'s own ascending-handle check therefore becomes a cross-check on the
 * snapshot rather than an obstacle to it.
 *
 * The one value `add` derives rather than accepts is the next periodic tick, which it sets to
 * `appliedTick + period`. A periodic effect that has already fired is wound forward with
 * `advancePeriod`, whose call count is `(captured next - applied) / period` and therefore exactly
 * the number of periods that had already elapsed.
 */
public class GameplayEffectsReplicator(
    override val typeId: ComponentTypeId = ComponentTypeId(DEFAULT_TYPE_ID),
) : Replicator<GameplayEffects> {

    override val fieldNames: List<String> = listOf("applied")

    /**
     * Empty, for the same reason [AbilitiesReplicator.netMask] is.
     *
     * A client is told what its own stats *are* ([Attributes]) and what happened to it (cues); the
     * ledger of applications behind them is server state. Snapshotted, never sent.
     */
    override val netMask: FieldMask = MaskOps.EMPTY

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: GameplayEffects, store: FieldStore, slot: Int) {
        store.setObject(slot, APPLIED, EffectVector.of(component))
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask =
        if (store.fieldEquals(slotA, slotB, APPLIED)) MaskOps.EMPTY else MaskOps.single(APPLIED)

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        vectorAt(store, slot).writeTo(out)
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, APPLIED)) store.setObject(slot, APPLIED, EffectVector.readFrom(src))
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: GameplayEffects, mask: FieldMask) {
        if (!MaskOps.test(mask, APPLIED)) return
        rebuild(vectorAt(store, slot), component)
    }

    override fun getField(component: GameplayEffects, fieldIndex: Int): Any? = when (fieldIndex) {
        APPLIED -> EffectVector.of(component)
        else -> throw NoSuchFieldIndexException("GameplayEffects", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: GameplayEffects, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            APPLIED -> rebuild(value as EffectVector, component)
            else -> throw NoSuchFieldIndexException("GameplayEffects", fieldIndex, FIELD_COUNT)
        }
    }

    private fun rebuild(vector: EffectVector, component: GameplayEffects) {
        component.clear()
        var effect = 0
        while (effect < vector.count) {
            val i = effect * EffectVector.INTS_PER_EFFECT
            val l = effect * EffectVector.LONGS_PER_EFFECT
            val period = vector.intAt(i + EffectVector.PERIOD_TICKS)
            val appliedTick = vector.longAt(l + EffectVector.APPLIED_TICK)
            val added = component.add(
                handle = EffectHandle(vector.intAt(i + EffectVector.HANDLE)),
                defIndex = vector.intAt(i + EffectVector.DEF_INDEX),
                appliedTick = Tick(appliedTick),
                durationTicks = vector.longAt(l + EffectVector.DURATION),
                periodTicks = period,
                source = NetId.ofRaw(vector.intAt(i + EffectVector.SOURCE)),
                stacks = vector.intAt(i + EffectVector.STACKS),
            )
            if (period > 0) {
                val periods = (vector.longAt(l + EffectVector.NEXT_PERIOD) - appliedTick) / period
                var wound = 1L
                while (wound < periods) {
                    component.advancePeriod(added)
                    wound++
                }
            }
            val f = effect * EffectVector.FLOATS_PER_EFFECT
            var offset = 0
            while (offset < GameplayEffects.SET_BY_CALLER_SLOTS) {
                val tag = GameplayTag(vector.intAt(i + EffectVector.TAG_FIRST + offset))
                if (tag != GameplayTag.NONE) {
                    component.setMagnitude(added, tag, vector.floatAt(f + offset))
                }
                offset++
            }
            effect++
        }
    }

    private fun vectorAt(store: FieldStore, slot: Int): EffectVector =
        store.getObject(slot, APPLIED) as? EffectVector
            ?: error("slot $slot holds no captured effect vector; capture before diffing or writing")

    public companion object {
        /** The one lowered field: the whole applied-effect list. */
        public const val APPLIED: Int = 0

        /** One field. */
        public const val FIELD_COUNT: Int = 1

        /** See [AttributesReplicator.DEFAULT_TYPE_ID]. */
        public const val DEFAULT_TYPE_ID: Int = 66
    }
}
