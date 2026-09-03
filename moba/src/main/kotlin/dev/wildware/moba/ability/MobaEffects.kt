package dev.wildware.moba.ability

import dev.wildware.udea.gas.AttributeId
import dev.wildware.udea.gas.GameplayEffectDef
import dev.wildware.udea.gas.GameplayEffectDuration
import dev.wildware.udea.gas.GameplayEffectTable
import dev.wildware.udea.gas.ModifierType
import dev.wildware.udea.gas.value

/**
 * Every gameplay effect in this game, and the table index of each.
 *
 * Ported from `assets/ability/gameplay_effects.udea.kts`, which is now a **packed** asset:
 * `gameplayEffect` yields a `dev.wildware.udea.assets.GameplayEffect`, so every effect below has
 * an authored declaration in the bundle this game ships.
 *
 * The table is still built here, and that is not a stub. A `GameplayEffectDef` holds an interned
 * `AttributeId`, a `TagSet` and an `IntArray` of cue ids - results of a *running* game's attribute
 * and tag tables - so it cannot be decoded from a bundle without one. What the publication removes
 * is the **unchecked** copy: `MobaAuthoredContentTest` compares the names, the durations and the
 * periods here against the authored ones, so retuning a period in the file a designer edits and
 * leaving this file alone is a red build rather than a silent divergence.
 *
 * (The KDoc that stood here promised `MobaAbilityContentTest` would pin the names. There was no
 * such test.)
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

    /** Modifies nothing and carries [MobaTags.DEAD]. Applied to a corpse by `DeathTagSystem`. */
    public val dead: Int = table.indexOf(DEAD)

    /**
     * The effect that carries an item's flat bonus to one attribute, by the authored stat name.
     *
     * The lookup `ItemPassiveSystem` does once per attribute per reconcile: an item asset writes
     * `stats = mapOf("strength" to 8F)` and this is what turns `"strength"` into the index of the
     * effect whose target is `strength`. A `GameplayEffectDef` has exactly one target attribute,
     * so an item's stat block is one application per attribute and there is no shape in which it
     * could be one.
     *
     * Iterated only through [ITEM_STATS], which is a list, so nothing here depends on hash order.
     */
    public val itemStatByName: Map<String, Int> =
        ITEM_STATS.associate { it.stat to table.indexOf(it.effect) }

    /** One named item passive: the effect an item's `unique` group grants once. */
    public class ItemPassive internal constructor(
        /** The [GameplayEffectDef] name, which is also the asset id an item's `passive` points at. */
        public val effect: String,
        /** Which attribute it moves. */
        public val attribute: (CharacterAttributes) -> AttributeId,
        /**
         * How much of it.
         *
         * The number is here and not in the asset because `EffectMagnitude` has no constant
         * case - `assets/item/stats.udea.kts` says so at length and names what closing it would
         * take. `ItemPassiveSystem` stages it onto the application under
         * [MobaTags.DATA_ITEM_STAT], exactly as an ability's cooldown ticks are staged onto
         * `ability/cooldown` rather than authored on it.
         */
        public val amount: Float,
    ) {
        override fun toString(): String = "ItemPassive($effect, $amount)"
    }

    /**
     * One authored item stat, and the effect that applies it.
     *
     * The stat name is the name an item asset writes as a key of its `stats` map; [attribute]
     * resolves the long, package-prefixed [CharacterAttributes] id it means.
     */
    public class ItemStat internal constructor(
        /** What an `item(...)` asset writes as a key of its `stats` map. */
        public val stat: String,
        /** The [GameplayEffectDef] name that applies it. */
        public val effect: String,
        /** Which attribute it modifies, off a running game's table. */
        public val attribute: (CharacterAttributes) -> AttributeId,
    ) {
        override fun toString(): String = "ItemStat($stat -> $effect)"
    }

    public companion object {

        public const val DAMAGE: String = "ability/damage"
        public const val HEAL: String = "ability/heal"
        public const val HEAL_OVER_TIME: String = "ability/heal_over_time"
        public const val STUN: String = "ability/stun"
        public const val COST_MANA: String = "ability/cost_mana"
        public const val COOLDOWN: String = "ability/cooldown"
        public const val PASSIVE_HEALTH_REGEN: String = "ability/passive_health_regen"

        /** @see MobaEffects.dead */
        public const val DEAD: String = "ability/dead"

        /**
         * Every attribute an item may raise, and the effect that raises it.
         *
         * ## Why an item's `"health"` is `maxHealth` and not `health`
         *
         * `health` is declared `max = value(maxHealth)` ([CharacterAttributes.bound]), and
         * `AttributeRecompute` clamps every modifier against that bound as it applies it. An
         * infinite additive modifier on `health` is therefore discarded in full on any unit at
         * full health, which is every champion that has just walked out of its own fountain - so
         * a `+80 health` item would have been a stat that did nothing, visibly, to the one player
         * who bought it first. Raising the ceiling is also what the genre means by the words: a
         * health item makes you harder to kill, not momentarily overhealed.
         *
         * The item assets say `maxHealth` and `maxMana` outright rather than relying on a mapping
         * here, so the file a designer edits names the attribute it moves. This list is the pairs
         * that exist, not an alias table.
         *
         * `magicResist` is absent because no item in this game raises it, and
         * `ItemPassiveSystem` refuses an unknown stat name loudly rather than dropping it - so
         * adding one is an authored stat plus a row here plus a `gameplayEffect(...)`, and a
         * missing row is a boot failure naming the stat rather than a bonus that never arrives.
         */
        public val ITEM_STATS: List<ItemStat> = listOf(
            ItemStat("strength", "item/stat_strength") { it.strength },
            ItemStat("armour", "item/stat_armour") { it.armour },
            ItemStat("maxHealth", "item/stat_max_health") { it.maxHealth },
            ItemStat("maxMana", "item/stat_max_mana") { it.maxMana },
            ItemStat("healthRegen", "item/stat_health_regen") { it.healthRegen },
        )

        /**
         * Every named item passive, one per unique group in the item tree.
         *
         * The effect name **is** the asset id an item's `passive` points at, which is what lets
         * `ItemPassiveSystem` turn a `Ref<GameplayEffect>` into a table index without a second
         * mapping to keep in step. A `passive` naming an effect that is not in this table is a
         * loud failure at reconcile rather than a bonus that silently never arrives.
         *
         * `item/passive_vigour` belongs to no unique group - `item/bloodletter` and
         * `item/archmage_staff` both name it and neither declares a `unique` - so a champion
         * carrying both carries two of it. It is here for the same reason the others are: what
         * makes an effect this system's to remove is being on this list, not being unique.
         *
         * `ability/passive_health_regen` is deliberately **not** here and no item names it.
         * `UnitBrain` applies that one to AI units, and a system that removed applications it did
         * not make would fight that one over the same unit every tick.
         */
        public val ITEM_PASSIVES: List<ItemPassive> = listOf(
            ItemPassive("item/passive_fortified", { it.armour }, amount = 15f),
            ItemPassive("item/passive_vitality", { it.maxHealth }, amount = 150f),
            ItemPassive("item/passive_sharpened", { it.strength }, amount = 10f),
            ItemPassive("item/passive_vigour", { it.healthRegen }, amount = 3f),
        )

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
                        GameplayEffectDef(
                            name = DEAD,
                            duration = GameplayEffectDuration.Infinite,
                            tags = tags.table.setOf(tags.dead),
                        ),
                    ) + (ITEM_STATS.map { it.effect to it.attribute } +
                        ITEM_PASSIVES.map { it.effect to it.attribute }).map { (name, attribute) ->
                        // One shape for both: an infinite additive modifier on one attribute whose
                        // magnitude the applier stages. They differ only in which attribute and in
                        // where the number comes from - a sum over the inventory for a stat, a
                        // constant for a passive - and both of those are `ItemPassiveSystem`'s.
                        GameplayEffectDef(
                            name = name,
                            target = attribute(attributes),
                            modifierType = ModifierType.Additive,
                            magnitude = value(tags.dataItemStat),
                            duration = GameplayEffectDuration.Infinite,
                            tags = tags.table.newSet(),
                        )
                    },
                ),
            )
    }
}
