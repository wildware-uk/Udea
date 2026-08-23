package dev.wildware.moba.ability

import dev.wildware.udea.gas.AbilityCost
import dev.wildware.udea.gas.AbilityDef
import dev.wildware.udea.gas.AbilityExecRegistry
import dev.wildware.udea.gas.AbilityTable
import dev.wildware.udea.gas.value

/**
 * Every ability in this game: its exec, its cooldown, its cost and what blocks it.
 *
 * Ported from `src/main/assets/ability/{npc_melee,orc_elite_abilities,priest_abilities,
 * soldier_abilities}.udea.kts`, one `ability(...)` call per [AbilityDef] here. Two differences
 * from the corpus, both of which the corpus's own migration comments predicted:
 *
 * - **Cooldowns are ticks.** `setByCaller = mapOf("Data.Cooldown" to 0.8F)` was seconds; it is
 *   `48` here, converted once. [dev.wildware.udea.gas.AbilityActivation] then stages that number
 *   onto the `ability/cooldown` effect as its duration, so the cooldown *is* an effect with a
 *   handle on the instance - which is what makes it survive a rewind.
 * - **`blockedBy` actually blocks.** The corpus notes that the original wrote
 *   `blockedBy = { Debuffs.Stunned }` - an expression statement in a list builder, so the list was
 *   empty and no ability was ever blocked by a stun. Every ability here is blocked by
 *   [MobaTags.STUNNED], and `AbilityActivation.tick` cancels one already running when the tag
 *   appears, which is the behaviour those assets always meant.
 */
public class MobaAbilities private constructor(
    /** Every definition. */
    public val table: AbilityTable,
    /** What each one wants to point at, for the autopilot. */
    public val targeting: AbilityTargeting,
) {

    /** The basic attack every unit has. */
    public val melee: Int = table.indexOf(MELEE)

    /** The elite orc's area attack. */
    public val orcSpin: Int = table.indexOf(ORC_SPIN)

    /** The priest's heal-over-time on nearby damaged allies. */
    public val priestHeal: Int = table.indexOf(PRIEST_HEAL)

    /** The soldier's arrow. */
    public val fireArrow: Int = table.indexOf(FIRE_ARROW)

    public companion object {

        public const val MELEE: String = "ability/npc_melee"
        public const val ORC_SPIN: String = "ability/orc_elite_spin"
        public const val PRIEST_HEAL: String = "ability/priest_heal"
        public const val FIRE_ARROW: String = "ability/soldier_fire_arrow"

        /** `Data.Cooldown to 0.8F`. */
        public const val MELEE_COOLDOWN_TICKS: Int = 48

        /** `Data.Cooldown to 15.0F`. */
        public const val ORC_SPIN_COOLDOWN_TICKS: Int = 900

        /** `Data.Cooldown to 10F`. */
        public const val PRIEST_HEAL_COOLDOWN_TICKS: Int = 600

        /** `Data.Cooldown to 5.0F`. */
        public const val FIRE_ARROW_COOLDOWN_TICKS: Int = 300

        /** `Cost.Mana to -10F` in the asset, declared positive here and negated when spent. */
        public const val PRIEST_HEAL_MANA_COST: Float = 10f

        /** Builds the table and the execs that run it. */
        public fun create(
            tags: MobaTags,
            attributes: CharacterAttributes,
            effects: MobaEffects,
            execs: AbilityExecRegistry,
            rules: CombatRules,
        ): MobaAbilities {
            val blockedByStun = tags.table.setOf(tags.stunned)
            val table = AbilityTable.of(
                listOf(
                    AbilityDef(
                        name = MELEE,
                        execId = execs.idOf(MeleeAttackExec::class.java.name),
                        cooldownTicks = MELEE_COOLDOWN_TICKS,
                        cooldownEffectIndex = effects.cooldown,
                        cooldownTag = tags.dataCooldown,
                        tags = tags.table.setOf(listOf(tags.hintDamage, tags.hintMelee)),
                        blockedBy = blockedByStun,
                    ),
                    AbilityDef(
                        name = ORC_SPIN,
                        execId = execs.idOf(OrcSpinExec::class.java.name),
                        cooldownTicks = ORC_SPIN_COOLDOWN_TICKS,
                        cooldownEffectIndex = effects.cooldown,
                        cooldownTag = tags.dataCooldown,
                        tags = tags.table.setOf(listOf(tags.hintAoe, tags.hintDamage, tags.hintMelee)),
                        blockedBy = blockedByStun,
                    ),
                    AbilityDef(
                        name = PRIEST_HEAL,
                        execId = execs.idOf(PriestHealExec::class.java.name),
                        cooldownTicks = PRIEST_HEAL_COOLDOWN_TICKS,
                        cooldownEffectIndex = effects.cooldown,
                        cooldownTag = tags.dataCooldown,
                        costs = listOf(
                            AbilityCost(
                                attribute = attributes.mana,
                                amount = value(PRIEST_HEAL_MANA_COST),
                                effectIndex = effects.costMana,
                                magnitudeTag = tags.costMana,
                            ),
                        ),
                        tags = tags.table.setOf(listOf(tags.hintAoe, tags.hintHeal)),
                        blockedBy = blockedByStun,
                    ),
                    AbilityDef(
                        name = FIRE_ARROW,
                        execId = execs.idOf(FireArrowExec::class.java.name),
                        cooldownTicks = FIRE_ARROW_COOLDOWN_TICKS,
                        cooldownEffectIndex = effects.cooldown,
                        cooldownTag = tags.dataCooldown,
                        tags = tags.table.setOf(listOf(tags.hintRanged, tags.hintDamage)),
                        blockedBy = blockedByStun,
                    ),
                ),
            )
            val targeting = AbilityTargeting(table.size).apply {
                declare(table.indexOf(MELEE), TargetPolicy(MeleeAttackExec.RANGE, TeamRelation.Enemy))
                declare(table.indexOf(ORC_SPIN), TargetPolicy(OrcSpinExec.RADIUS, TeamRelation.Enemy))
                declare(
                    table.indexOf(PRIEST_HEAL),
                    TargetPolicy(
                        range = PriestHealExec.RADIUS,
                        relation = TeamRelation.Friendly,
                        requiresDamaged = true,
                        includesSelf = true,
                    ),
                )
                declare(table.indexOf(FIRE_ARROW), TargetPolicy(FireArrowExec.RANGE, TeamRelation.Enemy))
            }
            check(rules.effects === effects) { "the rules and the ability table must share one effect table" }
            return MobaAbilities(table, targeting)
        }
    }
}

/**
 * What an ability needs to be pointed at before it is worth firing.
 *
 * `AbilityDef` carries no range, and deliberately: `udea-gas` has no space, so it cannot hold one
 * without inventing a coordinate system. The old corpus wrote `range = 0.5F` on the `ability(...)`
 * declaration and **nothing read it** - `UnitAISystem` used its own numbers - so the field was
 * decoration. Here the number lives beside the exec that uses it and the autopilot reads the same
 * one the ability does.
 */
public class TargetPolicy(
    /** How far the ability reaches. */
    public val range: Float,
    /** Who it is for. */
    public val relation: TeamRelation,
    /** Whether the candidate has to have lost health - a heal on a full unit is wasted. */
    public val requiresDamaged: Boolean = false,
    /** Whether the caster counts as its own target. */
    public val includesSelf: Boolean = false,
) {
    override fun toString(): String = "TargetPolicy($relation within $range)"
}

/** [TargetPolicy] per ability index. A dense array, because an ability index is one. */
public class AbilityTargeting(size: Int) {

    private val policies = arrayOfNulls<TargetPolicy>(size)

    /** Declares [policy] for [abilityIndex]. */
    public fun declare(abilityIndex: Int, policy: TargetPolicy) {
        policies[abilityIndex] = policy
    }

    /** [abilityIndex]'s policy, or `null` for an ability the autopilot must not fire. */
    public fun policyOf(abilityIndex: Int): TargetPolicy? =
        if (abilityIndex in policies.indices) policies[abilityIndex] else null
}
