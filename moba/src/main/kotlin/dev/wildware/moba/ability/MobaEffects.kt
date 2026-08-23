package dev.wildware.moba.ability

import dev.wildware.udea.gas.GameplayEffectDef
import dev.wildware.udea.gas.GameplayEffectDuration
import dev.wildware.udea.gas.GameplayEffectTable
import dev.wildware.udea.gas.ModifierType
import dev.wildware.udea.gas.value

/**
 * Every gameplay effect in this game, and the table index of each.
 *
 * Ported from `src/main/assets/ability/gameplay_effects.udea.kts`, which is data with no runtime
 * type behind it yet: `gameplayEffect` is `AssetKind.Unpublishable`, so the packer cannot publish
 * one and nothing loads these at boot. This is the runtime that corpus is waiting for. When #84
 * publishes the kind, this file becomes what the loader builds and these numbers move into the
 * scripts; until then it is written out, and `MobaAbilityContentTest` pins the names so the two
 * cannot drift silently.
 *
 * ## Durations are ticks
 *
 * Every duration in the old corpus was seconds (`Data.Duration to 1.0F`, `period = 0.25F`)
 * accumulated from a frame delta, so two machines running the same number of ticks disagreed
 * about when an effect ended. Here a period is a tick count, and the seconds it came from are
 * written beside it so a designer can still read the intent.
 *
 * ## What is deliberately absent: `ability/knockback`
 *
 * The corpus declares one, with `cues = listOf("KnockbackCue")` and no attribute target - an
 * effect whose entire behaviour was a Box2D impulse applied from inside a presentation cue. A
 * knockback is simulation, so it is applied by the ability as an impulse on [Motion], and the cue
 * ([MobaCues.KNOCKBACK]) is only the flash that goes with it. An effect that modifies no
 * attribute and carries no tag would otherwise be a handle and a list entry for nothing.
 */
public class MobaEffects private constructor(
    /** Every definition. */
    public val table: GameplayEffectTable,
) {

    /** Instant health change, magnitude staged by the caller. Negative for damage. */
    public val damage: Int = table.indexOf(DAMAGE)

    /** Instant heal. Positive magnitude. */
    public val heal: Int = table.indexOf(HEAL)

    /** The priest's heal: a periodic health change over a caller-set duration. */
    public val healOverTime: Int = table.indexOf(HEAL_OVER_TIME)

    /** Carries [MobaTags.STUNNED] for a caller-set number of ticks and modifies nothing. */
    public val stun: Int = table.indexOf(STUN)

    /** Spends mana. The magnitude is staged from the [dev.wildware.udea.gas.AbilityCost]. */
    public val costMana: Int = table.indexOf(COST_MANA)

    /** Holds an ability's cooldown. Its duration is the cooldown, in ticks. */
    public val cooldown: Int = table.indexOf(COOLDOWN)

    /** Restores `healthRegen` health once a second, forever. Applied by a unit's blueprint. */
    public val passiveHealthRegen: Int = table.indexOf(PASSIVE_HEALTH_REGEN)

    public companion object {

        public const val DAMAGE: String = "ability/damage"
        public const val HEAL: String = "ability/heal"
        public const val HEAL_OVER_TIME: String = "ability/heal_over_time"
        public const val STUN: String = "ability/stun"
        public const val COST_MANA: String = "ability/cost_mana"
        public const val COOLDOWN: String = "ability/cooldown"
        public const val PASSIVE_HEALTH_REGEN: String = "ability/passive_health_regen"

        /** How often `heal_over_time` fires: `250.milliseconds` in the old corpus, at 60Hz. */
        public const val HEAL_PERIOD_TICKS: Int = 15

        /** How often passive regen fires. One second, as `period = 1.0F` said. */
        public const val REGEN_PERIOD_TICKS: Int = 60

        /** Builds the table over [tags] and [attributes]. */
        public fun create(tags: MobaTags, attributes: CharacterAttributes): MobaEffects =
            MobaEffects(
                GameplayEffectTable.of(
                    listOf(
                        GameplayEffectDef(
                            name = DAMAGE,
                            target = attributes.health,
                            modifierType = ModifierType.Additive,
                            magnitude = value(tags.dataDamage),
                            duration = GameplayEffectDuration.Instant,
                            tags = tags.table.newSet(),
                            cueIds = intArrayOf(MobaCues.DAMAGE),
                        ),
                        GameplayEffectDef(
                            name = HEAL,
                            target = attributes.health,
                            modifierType = ModifierType.Additive,
                            magnitude = value(tags.dataHeal),
                            duration = GameplayEffectDuration.Instant,
                            tags = tags.table.newSet(),
                            cueIds = intArrayOf(MobaCues.HEAL),
                        ),
                        GameplayEffectDef(
                            name = HEAL_OVER_TIME,
                            target = attributes.health,
                            modifierType = ModifierType.Additive,
                            magnitude = value(tags.dataHeal),
                            duration = GameplayEffectDuration.SetByCaller(tags.dataDuration),
                            periodTicks = HEAL_PERIOD_TICKS,
                            tags = tags.table.newSet(),
                            cueIds = intArrayOf(MobaCues.HEAL),
                        ),
                        GameplayEffectDef(
                            name = STUN,
                            duration = GameplayEffectDuration.SetByCaller(tags.dataDuration),
                            tags = tags.table.setOf(tags.stunned),
                        ),
                        GameplayEffectDef(
                            name = COST_MANA,
                            target = attributes.mana,
                            modifierType = ModifierType.Additive,
                            magnitude = value(tags.costMana),
                            duration = GameplayEffectDuration.Instant,
                            tags = tags.table.newSet(),
                        ),
                        GameplayEffectDef(
                            name = COOLDOWN,
                            duration = GameplayEffectDuration.SetByCaller(tags.dataCooldown),
                            tags = tags.table.newSet(),
                        ),
                        GameplayEffectDef(
                            name = PASSIVE_HEALTH_REGEN,
                            target = attributes.health,
                            modifierType = ModifierType.Additive,
                            magnitude = value(attributes.healthRegen),
                            duration = GameplayEffectDuration.Infinite,
                            periodTicks = REGEN_PERIOD_TICKS,
                            tags = tags.table.newSet(),
                        ),
                    ),
                ),
            )
    }
}
