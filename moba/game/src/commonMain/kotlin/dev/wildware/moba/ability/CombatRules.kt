package dev.wildware.moba.ability

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.gas.AbilityContext

/**
 * The four things an ability in this game does to somebody else, in one place.
 *
 * Every exec holds one. It is not a base class: an exec is a stateless singleton and inheritance
 * would invite a subclass to add a field, which is precisely the mistake `udea-gas` exists to
 * make impossible (per-activation state belongs on the [dev.wildware.udea.gas.AbilityInstance]).
 *
 * ## Frame timings
 *
 * The tick offsets on each exec are the old animation notifies converted at `MobaScene`'s
 * playhead of six ticks per frame: `soldier_attack` was six frames with `attack_hit` on frame 4,
 * so the hit lands 24 ticks in and the swing ends at 36. When the animation system lands, those
 * numbers come from the sheet instead of from a constant - and because they are already
 * tick-denominated, nothing about the simulation changes when they do.
 */
public class CombatRules(
    /** The tag vocabulary. */
    public val tags: MobaTags,
    /** The attribute ids. */
    public val attributes: CharacterAttributes,
    /** The effect table indices. */
    public val effects: MobaEffects,
    /** How an exec reaches the units around it. */
    public val combat: CombatWorldRef,
) {

    /**
     * Applies [amount] of damage to [target] and returns whether it landed.
     *
     * [amount] is positive; it is negated once, here, because `ability/damage` is additive on
     * health. The old code wrote `-strength * 1.5F` at each of three call sites and a fourth site
     * wrote `-10F` into an asset - one sign error away from an ability that healed its victim.
     */
    public fun damage(context: AbilityContext, target: NetId, amount: Float): Boolean {
        val world = combat.world
        val targetAttributes = world.attributesOf(target) ?: return false
        val targetEffects = world.effectsOf(target) ?: return false
        context.applier
            .begin(effects.damage)
            .magnitude(tags.dataDamage, -amount)
            .applyTo(targetEffects, targetAttributes, context.tick, targetId = target, source = context.self)
        return true
    }

    /** Applies `ability/stun` to [target] for [durationTicks]. */
    public fun stun(context: AbilityContext, target: NetId, durationTicks: Int): Boolean {
        val world = combat.world
        val targetAttributes = world.attributesOf(target) ?: return false
        val targetEffects = world.effectsOf(target) ?: return false
        context.applier
            .begin(effects.stun)
            .magnitude(tags.dataDuration, durationTicks.toFloat())
            .applyTo(targetEffects, targetAttributes, context.tick, targetId = target, source = context.self)
        return true
    }

    /**
     * Applies `ability/heal_over_time` to [target]: [perPeriod] health every
     * [MobaEffects.HEAL_PERIOD_TICKS] for [durationTicks].
     */
    public fun healOverTime(
        context: AbilityContext,
        target: NetId,
        perPeriod: Float,
        durationTicks: Int,
    ): Boolean {
        val world = combat.world
        val targetAttributes = world.attributesOf(target) ?: return false
        val targetEffects = world.effectsOf(target) ?: return false
        context.applier
            .begin(effects.healOverTime)
            .magnitude(tags.dataHeal, perPeriod)
            .magnitude(tags.dataDuration, durationTicks.toFloat())
            .applyTo(targetEffects, targetAttributes, context.tick, targetId = target, source = context.self)
        return true
    }

    /**
     * Pushes [target] directly away from [fromX], [fromY] with [strength] world units per tick,
     * and emits [MobaCues.KNOCKBACK] carrying the impulse.
     *
     * This is the half of the old `KnockbackCue` that was never presentation. The cue it emits
     * *is* presentation and carries the vector, so a renderer can lean the sprite into it without
     * the simulation depending on whether anybody listened.
     */
    public fun knockback(
        context: AbilityContext,
        target: NetId,
        fromX: Float,
        fromY: Float,
        strength: Float,
    ) {
        val world = combat.world
        if (!world.contains(target)) return
        var dx = world.x(target) - fromX
        var dy = world.y(target) - fromY
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length < EPSILON) {
            // Exactly co-located, which two spawned units can genuinely be. Pushing along +x is
            // arbitrary but deterministic; a random direction here would need an RngService stream
            // and would still be arbitrary.
            dx = 1f
            dy = 0f
        } else {
            dx /= length
            dy /= length
        }
        world.impulse(target, dx * strength, dy * strength)
        context.cues.emit(
            cueId = MobaCues.KNOCKBACK,
            tick = context.tick,
            source = context.self,
            target = target,
            payload0 = dx * strength,
            payload1 = dy * strength,
        )
    }

    /** [id]'s current health, or zero if it is gone. */
    public fun healthOf(id: NetId): Float =
        combat.world.attributesOf(id)?.current(attributes.health) ?: 0f

    /** [id]'s maximum health, or zero if it is gone. */
    public fun maxHealthOf(id: NetId): Float =
        combat.world.attributesOf(id)?.current(attributes.maxHealth) ?: 0f

    /** Whether [id] has lost health worth healing. */
    public fun isDamaged(id: NetId): Boolean {
        val values = combat.world.attributesOf(id) ?: return false
        return values.current(attributes.health) < values.current(attributes.maxHealth)
    }

    private companion object {
        /** Below this separation two units count as co-located. */
        const val EPSILON: Float = 1e-4f
    }
}
