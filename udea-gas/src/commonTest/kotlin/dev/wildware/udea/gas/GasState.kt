package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick

/**
 * Captures and restores exactly the GAS state a snapshot must carry — by hand.
 *
 * `udea-core`'s `SnapshotService` captures Fleks components through generated `Replicator`s and
 * carries a `HandleState` for entity ids; it has no hook for a module's own allocator and no
 * generated replicator exists for [GameplayEffects] yet. Rather than claim a snapshot integration
 * this module does not have, these tests capture the same *fields* a replicator will — every
 * primitive column of the effect list, the base values, the instance and the handle counter — and
 * assert the equivalence property the ring will need.
 *
 * What that does **not** prove is that the ring wires GAS in. It proves that GAS state is
 * capturable: it is all primitives, nothing is derived-but-captured, and a restored world
 * re-simulates to the same numbers. The wiring is a `udea-core`-side hook and is not this module's.
 */
internal class GasState private constructor(
    private val base: FloatArray,
    private val handleState: HandleAllocatorState,
    private val handles: IntArray,
    private val defs: IntArray,
    private val applied: LongArray,
    private val durations: LongArray,
    private val periods: IntArray,
    private val nextPeriods: LongArray,
    private val magnitudeTags: IntArray,
    private val magnitudeValues: FloatArray,
    private val instances: Array<InstanceState>,
    private val instanceCounter: Int,
) {

    /** One [AbilityInstance]'s fields, all of them primitives. */
    internal class InstanceState(instance: AbilityInstance) {
        private val abilityIndex = instance.abilityIndex
        private val instanceId = instance.instanceId
        private val phase = instance.phase
        private val activatedTick = instance.activatedTick
        private val cooldownHandle = instance.cooldownHandle
        private val predictionKey = instance.predictionKey
        private val targetKind = instance.targetKind
        private val targetId = instance.targetId
        private val targetX = instance.targetX
        private val targetY = instance.targetY
        private val scratchFloats = instance.scratchFloats.copyOf()
        private val scratchInts = instance.scratchInts.copyOf()

        /** Writes these values back onto [target]. */
        fun restoreInto(target: AbilityInstance) {
            target.abilityIndex = abilityIndex
            target.instanceId = instanceId
            target.phase = phase
            target.activatedTick = activatedTick
            target.cooldownHandle = cooldownHandle
            target.predictionKey = predictionKey
            target.targetKind = targetKind
            target.targetId = targetId
            target.targetX = targetX
            target.targetY = targetY
            scratchFloats.copyInto(target.scratchFloats)
            scratchInts.copyInto(target.scratchInts)
        }
    }

    companion object {

        /** Captures everything about [unit] and [allocator] that must survive a restore. */
        fun capture(unit: GasFixture.Unit, allocator: HandleAllocator): GasState {
            val effects = unit.effects
            val slots = effects.count
            val entries = GameplayEffects.SET_BY_CALLER_SLOTS
            return GasState(
                base = unit.attributes.base.copyOf(),
                handleState = HandleAllocatorState().also { allocator.saveInto(it) },
                handles = IntArray(slots) { effects.handleAt(it).raw },
                defs = IntArray(slots) { effects.defIndexAt(it) },
                applied = LongArray(slots) { effects.appliedTickAt(it).value },
                durations = LongArray(slots) { effects.durationTicksAt(it) },
                periods = IntArray(slots) { effects.periodTicksAt(it) },
                nextPeriods = LongArray(slots) { effects.nextPeriodTickAt(it).value },
                magnitudeTags = IntArray(slots * entries) {
                    effects.magnitudeTagAt(it / entries, it % entries).id
                },
                magnitudeValues = FloatArray(slots * entries) {
                    effects.magnitudeValueAt(it / entries, it % entries)
                },
                instances = Array(unit.abilities.slotCount) {
                    InstanceState(unit.abilities.instanceAt(it))
                },
                instanceCounter = unit.abilities.instanceCounter,
            )
        }

        /** Captures one instance, for a test that only cares about a mid-cast ability. */
        fun captureInstance(instance: AbilityInstance): InstanceState = InstanceState(instance)

        /** Writes [state] back onto [unit] and [allocator], discarding whatever they held. */
        fun restore(state: GasState, unit: GasFixture.Unit, allocator: HandleAllocator) {
            state.base.copyInto(unit.attributes.base)
            allocator.restoreFrom(state.handleState)

            val effects = unit.effects
            val entries = GameplayEffects.SET_BY_CALLER_SLOTS
            effects.clear()
            for (index in state.handles.indices) {
                val slot = effects.add(
                    handle = EffectHandle(state.handles[index]),
                    defIndex = state.defs[index],
                    appliedTick = Tick(state.applied[index]),
                    durationTicks = state.durations[index],
                    periodTicks = state.periods[index],
                )
                for (entry in 0 until entries) {
                    val tag = GameplayTag(state.magnitudeTags[index * entries + entry])
                    if (tag != GameplayTag.NONE) {
                        effects.setMagnitude(slot, tag, state.magnitudeValues[index * entries + entry])
                    }
                }
                while (effects.nextPeriodTickAt(slot).value < state.nextPeriods[index]) {
                    effects.advancePeriod(slot)
                }
            }

            for (index in state.instances.indices) {
                state.instances[index].restoreInto(unit.abilities.instanceAt(index))
            }
            unit.abilities.restoreInstanceCounter(state.instanceCounter)
        }
    }
}
