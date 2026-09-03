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
 * A set of ability slots that cool down together.
 *
 * A property of the **slot**, never of the [AbilityDef], and that distinction is the whole of it:
 * one definition can be both a champion's own ability and the active an item grants — `moba`'s
 * `item/aegis` grants `ability/priest_heal`, which is also the priest's own ability — so a group
 * stored on the definition would put the champion's heal and the item's heal in one group and
 * cool them down together, which is precisely the independence an item active must have.
 *
 * [NONE] means "this slot cools down alone", which is every slot in a game that declares no
 * sharing.
 */
@JvmInline
public value class CooldownGroup(public val index: Int) {
    override fun toString(): String = if (index < 0) "CooldownGroup.NONE" else "CooldownGroup#$index"

    public companion object {
        /** The group that shares with nothing. */
        public val NONE: CooldownGroup = CooldownGroup(-1)
    }
}

/**
 * Which ability slots share a cooldown, as a policy the game supplies once.
 *
 * World-level and not per-entity: a slot layout is a property of the game's ability bar, so there
 * is nothing here for a snapshot to carry and no two entities that can disagree about it. A game
 * wanting "the item slots share one cooldown" writes one lambda; [None] is what every other game
 * gets, and it is the default on [AbilityActivation].
 */
public fun interface CooldownSharing {

    /** The group [slot] cools down with, or [CooldownGroup.NONE] when it cools down alone. */
    public fun groupOf(slot: Int): CooldownGroup

    public companion object {
        /** Every slot cools down alone. */
        public val None: CooldownSharing = CooldownSharing { CooldownGroup.NONE }
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
    /** Which slots cool down together. See [CooldownSharing]. */
    private val sharing: CooldownSharing = CooldownSharing.None,
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
            val handle = applier.begin(def.cooldownEffectIndex)
                .magnitude(def.cooldownTag, ticks.toFloat())
                .applyTo(effects, attributes, now, targetId = self, source = self)
            instance.cooldownHandle = handle
            shareCooldown(abilities, slot, handle)
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

    /**
     * Grants [abilityIndex] into [slot], adopting whatever cooldown its group is already serving.
     *
     * The group-aware replacement for [Abilities.grant], which resets the instance and therefore
     * clears its cooldown handle. Without the adoption a shared cooldown has a hole in it that a
     * player finds in one match: with `moba`'s two item slots sharing one cooldown, firing the
     * active in the first slot and then *buying* a second active would hand the new slot a fresh
     * instance with [EffectHandle.INVALID] on it, so the group's cooldown would not apply to it
     * and the second active would fire immediately.
     *
     * The handle is taken from the peer with the most cooldown left, so what the new slot adopts
     * is the cooldown a player can see rather than whichever peer happened to be scanned first.
     *
     * ## What it does not do
     *
     * A group whose every slot is ungranted holds no handle, so emptying every slot in a group
     * and granting into one again starts with no cooldown. In `moba` that is "sell both item
     * actives and buy another one", which costs more gold than the cooldown is worth; closing it
     * needs the group's cooldown stored somewhere other than on the slots, which is a replicated
     * component this issue deliberately does not add.
     */
    public fun grant(
        abilities: Abilities,
        effects: GameplayEffects,
        slot: Int,
        abilityIndex: Int,
        now: Tick,
    ) {
        abilities.grant(slot, abilityIndex)
        val group = sharing.groupOf(slot)
        if (group == CooldownGroup.NONE) return

        var best = EffectHandle.INVALID
        var bestRemaining = 0
        var peer = 0
        while (peer < abilities.slotCount) {
            if (peer != slot && sharing.groupOf(peer) == group) {
                val remaining = cooldownRemaining(abilities, effects, peer, now)
                if (remaining > bestRemaining) {
                    bestRemaining = remaining
                    best = abilities.instanceAt(peer).cooldownHandle
                }
            }
            peer++
        }
        if (!best.isInvalid) abilities.instanceAt(slot).cooldownHandle = best
    }

    /**
     * Points every other slot in [slot]'s cooldown group at [handle].
     *
     * Every slot in the group and not only the granted ones: an ungranted slot that is later
     * granted through [Abilities.grant] resets anyway, and one granted through [grant] reads the
     * peers — so writing to all of them is what makes an empty item slot hold the group's cooldown
     * for whatever is bought into it next.
     *
     * It can never shorten a cooldown already running, because [canActivate] refuses every member
     * of a group while any member's handle resolves to a live application: nothing in the group
     * can activate, so nothing in the group can overwrite.
     */
    private fun shareCooldown(abilities: Abilities, slot: Int, handle: EffectHandle) {
        val group = sharing.groupOf(slot)
        if (group == CooldownGroup.NONE) return
        var peer = 0
        while (peer < abilities.slotCount) {
            if (peer != slot && sharing.groupOf(peer) == group) {
                abilities.instanceAt(peer).cooldownHandle = handle
            }
            peer++
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
