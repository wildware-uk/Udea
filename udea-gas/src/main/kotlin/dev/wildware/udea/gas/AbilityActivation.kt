package dev.wildware.udea.gas

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId

/**
 * Whether this simulation may activate abilities on a given entity.
 *
 * A policy rather than a `NetRole` check inside the activation code, because "may I act on this
 * entity" is a question about *that entity* — a client controls one champion and predicts for it,
 * while a listen server controls one and is authoritative for everything else. The old code asked
 * the global `gameScreen.isServer` and answered for the whole process
 * (`common/.../Abilities.kt:76`), which is why clients were never granted abilities at all.
 */
public fun interface AbilityAuthority {
    /** True when this simulation may activate [entity]'s abilities. */
    public fun controls(entity: NetId): Boolean

    public companion object {
        /** Everything is controllable. A standalone simulation, and the default in tests. */
        public val All: AbilityAuthority = AbilityAuthority { true }
    }
}

/**
 * Gates activation on cooldowns, costs and blocking tags, and runs in-flight activations.
 *
 * World-free on purpose: it takes the two components and a tick, so every rule it enforces can be
 * tested without a Fleks world, and `AbilitySystem` is a nine-line adapter over it.
 *
 * ## What it fixes
 *
 * - **Cooldown survives a rewind.** A cooldown used to be an effect found by a handle from a
 *   process-wide static counter (`Ability.kt:82`, set at `:134`, read at `:151`), so after a
 *   rewind the handle resolved to nothing and the ability came off cooldown early. The handle now
 *   comes from the per-world [HandleAllocator] and lives on the [AbilityInstance], both of which
 *   are snapshot state.
 * - **Costs are actually checked.** `checkCosts()` at `Ability.kt:145-147` had an empty body.
 * - **Refusals are typed.** [canActivate] runs every check before anything mutates, and returns
 *   an [ActivationResult] that names the reason — for the HUD, and for the agent's
 *   `activate_ability` tool.
 */
public class AbilityActivation(
    private val abilityTable: AbilityTable,
    private val effectTable: GameplayEffectTable,
    private val execs: AbilityExecRegistry,
    private val applier: EffectApplier,
    private val cues: GasCueQueue,
    private val authority: AbilityAuthority = AbilityAuthority.All,
) {

    /** Reused for the "which tags does this entity have right now" question. Never allocated per call. */
    private val tagScratch: TagSet = TagSet(TAG_SCRATCH_BITS)

    private val context = AbilityContext(effectTable, abilityTable, applier, cues)

    private val costCursor = CostCursor()

    /**
     * Whether [slot] could activate right now, and if not, why.
     *
     * Evaluated in full before anything is mutated, so calling this and then [activate] never
     * leaves half an activation behind, and calling it alone never changes the world.
     */
    public fun canActivate(
        self: NetId,
        abilities: Abilities,
        attributes: Attributes,
        effects: GameplayEffects,
        slot: Int,
        now: Tick,
    ): ActivationResult {
        val instance = abilities.instanceAt(slot)
        if (!instance.isGranted) return ActivationResult.NotGranted
        if (!authority.controls(self)) return ActivationResult.NoAuthority
        if (instance.isActive) return ActivationResult.AlreadyActive

        val def = abilityTable.defAt(instance.abilityIndex)

        val blocking = blockingTag(def, effects)
        if (blocking != GameplayTag.NONE) return ActivationResult.BlockedByTag(blocking)

        val remaining = cooldownRemaining(abilities, effects, slot, now)
        if (remaining > 0) return ActivationResult.OnCooldown(remaining)

        costCursor.bind(attributes)
        for (cost in def.costs) {
            val required = cost.amount.resolve(costCursor)
            val available = attributes.current(cost.attribute)
            if (available < required) {
                return ActivationResult.InsufficientResource(cost.attribute, required, available)
            }
        }
        return ActivationResult.Activated
    }

    /**
     * Activates [slot] if it can, paying costs and starting the cooldown.
     *
     * @return the same [ActivationResult] [canActivate] would have. On anything but
     *   [ActivationResult.Activated] nothing was mutated: no attribute changed, no cooldown
     *   started and no cue was emitted.
     */
    public fun activate(
        self: NetId,
        abilities: Abilities,
        attributes: Attributes,
        effects: GameplayEffects,
        slot: Int,
        now: Tick,
    ): ActivationResult {
        val gate = canActivate(self, abilities, attributes, effects, slot, now)
        if (gate !== ActivationResult.Activated) return gate

        val instance = abilities.instanceAt(slot)
        val def = abilityTable.defAt(instance.abilityIndex)

        // Reset first: it clears the previous activation's handle, target and scratch, and doing it
        // after the cooldown was applied would throw that handle away — which is precisely the
        // "ability comes off cooldown early" defect this issue exists to fix.
        instance.reset()

        costCursor.bind(attributes)
        for (cost in def.costs) {
            applier.begin(cost.effectIndex)
            // The amount the gate just checked, staged for the effect that spends it. Without
            // this the cost effect's `SetByCaller` magnitude resolved to zero and **no cost was
            // ever paid**: `canActivate` refused an activation the entity could not afford and
            // then the activation it allowed took nothing, so mana was a gate and never a
            // resource. Negated once, here, because the amount is declared positive and the
            // effect is additive on the attribute it spends.
            if (cost.magnitudeTag != GameplayTag.NONE) {
                applier.magnitude(cost.magnitudeTag, -cost.amount.resolve(costCursor))
            }
            applier.applyTo(effects, attributes, now, targetId = self, source = self)
        }

        if (def.cooldownEffectIndex >= 0) {
            val ticks = effectiveCooldownTicks(def, attributes)
            instance.cooldownHandle = applier.begin(def.cooldownEffectIndex)
                .magnitude(def.cooldownTag, ticks.toFloat())
                .applyTo(effects, attributes, now, targetId = self, source = self)
        }

        instance.instanceId = abilities.nextInstanceId()
        instance.phase = AbilityPhase.Active
        instance.activatedTick = now

        context.bind(instance, self, attributes, effects, now)
        execs.execAt(def.execId).onActivate(context)
        // After the call and never inside it: `end` runs `onEnd` through this same context, so
        // ending from within `onActivate` would rebind mid-call.
        if (context.endRequested) endIfRequested(instance, def)
        return ActivationResult.Activated
    }

    /**
     * Advances every in-flight activation on this entity by one tick, cancelling any whose
     * ability is blocked by a tag the entity has acquired since.
     *
     * The cancel-on-blocking-tag path is ported from `AbilitySystem.kt:45-47`, where it was the
     * one piece of that loop worth keeping.
     */
    public fun tick(
        self: NetId,
        abilities: Abilities,
        attributes: Attributes,
        effects: GameplayEffects,
        now: Tick,
    ) {
        var slot = 0
        while (slot < abilities.slotCount) {
            val instance = abilities.instanceAt(slot)
            if (instance.isGranted && instance.isActive) {
                val def = abilityTable.defAt(instance.abilityIndex)
                context.bind(instance, self, attributes, effects, now)
                if (blockingTag(def, effects) != GameplayTag.NONE) {
                    end(instance, def, cancelled = true)
                } else {
                    execs.execAt(def.execId).onTick(context)
                    if (context.endRequested) endIfRequested(instance, def)
                }
            }
            slot++
        }
    }

    /** Ends [slot]'s activation, running the exec's `onEnd`. */
    public fun end(
        self: NetId,
        abilities: Abilities,
        attributes: Attributes,
        effects: GameplayEffects,
        slot: Int,
        now: Tick,
        cancelled: Boolean = false,
    ) {
        val instance = abilities.instanceAt(slot)
        if (!instance.isActive) return
        val def = abilityTable.defAt(instance.abilityIndex)
        context.bind(instance, self, attributes, effects, now)
        end(instance, def, cancelled)
    }

    /** Runs the end an exec asked for with [AbilityContext.endAbility], clearing the request. */
    private fun endIfRequested(instance: AbilityInstance, def: AbilityDef) {
        context.endRequested = false
        if (instance.isActive) end(instance, def, cancelled = false)
    }

    private fun end(instance: AbilityInstance, def: AbilityDef, cancelled: Boolean) {
        execs.execAt(def.execId).onEnd(context, cancelled)
        instance.phase = AbilityPhase.Inactive
        instance.clearTarget()
    }

    // --- queries -----------------------------------------------------------------------------

    /**
     * Ticks until [slot] comes off cooldown, or `0`.
     *
     * The query both the HUD and the agent surface read. It resolves through the instance's
     * handle, so it still answers correctly after a snapshot restore.
     */
    public fun cooldownRemaining(
        abilities: Abilities,
        effects: GameplayEffects,
        slot: Int,
        now: Tick,
    ): Int {
        val handle = abilities.instanceAt(slot).cooldownHandle
        if (handle.isInvalid) return 0
        val effectSlot = effects.indexOfHandle(handle)
        if (effectSlot < 0) return 0
        val duration = effects.durationTicksAt(effectSlot)
        if (duration == GameplayEffectDuration.INFINITE) return Int.MAX_VALUE
        val expiresAt = effects.appliedTickAt(effectSlot).value + duration
        val remaining = expiresAt - now.value
        return if (remaining <= 0L) 0 else remaining.toInt()
    }

    /** True when [slot] is cooling down. */
    public fun isOnCooldown(abilities: Abilities, effects: GameplayEffects, slot: Int, now: Tick): Boolean =
        cooldownRemaining(abilities, effects, slot, now) > 0

    /**
     * [def]'s cooldown after reduction, in whole ticks, computed in integer arithmetic.
     *
     * The reduction is an ordinary attribute — a percentage — so items and buffs in Phase 5 modify
     * it like any other stat without an engine change. It is converted to basis points **once**,
     * with a documented rounding, and every step after that is integer: `ticks - ticks * bp /
     * 10000`. A 20% reduction on 900 ticks is exactly 720, on every machine, with no float in the
     * result.
     *
     * Reduction is clamped to [MAX_REDUCTION_BASIS_POINTS] so stacked items cannot drive a
     * cooldown to zero or negative.
     */
    public fun effectiveCooldownTicks(def: AbilityDef, attributes: Attributes): Int {
        if (def.cooldownReductionAttribute == AttributeId.NONE) return def.cooldownTicks
        val percent = attributes.current(def.cooldownReductionAttribute)
        val basisPoints = Math.round(percent * BASIS_POINTS_PER_PERCENT)
            .coerceIn(0, MAX_REDUCTION_BASIS_POINTS)
        val reduction = def.cooldownTicks.toLong() * basisPoints / BASIS_POINTS_FULL
        return (def.cooldownTicks - reduction).toInt()
    }

    /** The first tag [def] is blocked by that this entity currently has, or [GameplayTag.NONE]. */
    private fun blockingTag(def: AbilityDef, effects: GameplayEffects): GameplayTag {
        if (def.blockedBy.isEmpty) return GameplayTag.NONE
        effects.collectTags(effectTable, tagScratch)
        return def.blockedBy.firstIntersection(tagScratch)
    }

    /** Resolves a cost magnitude. A cost has no application yet, so set-by-caller reads as zero. */
    private class CostCursor : MagnitudeSource {

        private var attributes: Attributes? = null

        fun bind(attributes: Attributes) {
            this.attributes = attributes
        }

        override fun attribute(id: AttributeId): Float =
            checkNotNull(attributes) { "cost cursor is unbound" }.current(id)

        override fun setByCaller(tag: GameplayTag): Float = 0f
    }

    public companion object {
        /** Basis points in one percent. */
        private const val BASIS_POINTS_PER_PERCENT: Float = 100f

        /** Basis points in the whole. */
        private const val BASIS_POINTS_FULL: Long = 10_000L

        /** Cooldown reduction is capped at 80%, so stacking cannot reach a zero cooldown. */
        public const val MAX_REDUCTION_BASIS_POINTS: Int = 8_000

        /**
         * How many tag ids the blocking-tag scratch covers.
         *
         * Sized once, generously, because a [TagSet] is one bit per tag: 1024 tags is 128 bytes,
         * held once per activation object rather than per entity. A game with more than this many
         * tags gets a loud failure from [TagSet.add] rather than a silent miss.
         */
        public const val TAG_SCRATCH_BITS: Int = 1024
    }
}
