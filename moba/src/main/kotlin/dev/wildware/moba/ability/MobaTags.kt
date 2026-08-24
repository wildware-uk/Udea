package dev.wildware.moba.ability

import dev.wildware.udea.gas.GameplayTag
import dev.wildware.udea.gas.GameplayTagTable

/**
 * This game's gameplay-tag vocabulary, resolved once into ids.
 *
 * ## What this replaces
 *
 * `example/.../ability/ExampleTags.kt` declared five enums implementing a `GameplayTag`
 * *interface*, so a tag was a JVM object: comparing two of them was `equals`, a set of them was a
 * `HashSet`, and every `addDynamicTag` boxed. `udea-gas` makes a tag an `Int` in a table and a set
 * of tags a bitset ([dev.wildware.udea.gas.TagSet]), so the same question - "is this unit stunned"
 * - is an `and` of two longs. The vocabulary is unchanged; only its representation is.
 *
 * The names carry the old enum's own dots (`Debuffs.Stunned`, not `Stunned`), because the
 * `.udea.kts` corpus under `assets/` writes them that way and an asset that says
 * `blockedBy = listOf("Debuffs.Stunned")` has to resolve against this table when #84 wires the
 * corpus to the runtime. Getting that string wrong today is a `NoSuchTagException` at boot rather
 * than a tag that silently never matches.
 */
public class MobaTags private constructor(
    /** Every tag in this game, by name. */
    public val table: GameplayTagTable,
) {

    /** Blocks every ability that names it in `blockedBy`, and cancels one already running. */
    public val stunned: GameplayTag = table.tagOf(STUNNED)

    /** Damage schools. Carried on an application so a resistance can read it. */
    public val physical: GameplayTag = table.tagOf(PHYSICAL)
    public val magic: GameplayTag = table.tagOf(MAGIC)

    /** Set-by-caller keys: the magnitudes an ability stages onto the effect it applies. */
    public val dataDamage: GameplayTag = table.tagOf(DATA_DAMAGE)
    public val dataHeal: GameplayTag = table.tagOf(DATA_HEAL)
    public val dataDuration: GameplayTag = table.tagOf(DATA_DURATION)
    public val dataCooldown: GameplayTag = table.tagOf(DATA_COOLDOWN)
    public val costMana: GameplayTag = table.tagOf(COST_MANA)

    /** What the AI reads to decide what an ability is *for*. */
    public val hintDamage: GameplayTag = table.tagOf(HINT_DAMAGE)
    public val hintHeal: GameplayTag = table.tagOf(HINT_HEAL)
    public val hintMelee: GameplayTag = table.tagOf(HINT_MELEE)
    public val hintRanged: GameplayTag = table.tagOf(HINT_RANGED)
    public val hintAoe: GameplayTag = table.tagOf(HINT_AOE)

    public companion object {

        public const val STUNNED: String = "Debuffs.Stunned"
        public const val PHYSICAL: String = "Damage.Physical"
        public const val MAGIC: String = "Damage.Magic"
        public const val TRUE_DAMAGE: String = "Damage.True"

        public const val DATA_DAMAGE: String = "Data.Damage"
        public const val DATA_KNOCKBACK: String = "Data.Knockback"
        public const val DATA_DURATION: String = "Data.Duration"
        public const val DATA_HEAL: String = "Data.Heal"
        public const val DATA_COOLDOWN: String = "Data.Cooldown"

        public const val COST_MANA: String = "Cost.Mana"
        public const val COST_HEALTH: String = "Cost.Health"

        public const val HINT_HEAL: String = "AIHint.Heal"
        public const val HINT_DAMAGE: String = "AIHint.Damage"
        public const val HINT_AOE: String = "AIHint.AOE"
        public const val HINT_MELEE: String = "AIHint.Melee"
        public const val HINT_RANGED: String = "AIHint.Ranged"
        public const val HINT_TARGET_ENEMY: String = "AIHint.TargetEnemy"
        public const val HINT_TARGET_FRIENDLY: String = "AIHint.TargetFriendly"
        public const val FEARLESS: String = "AITag.Fearless"

        /**
         * Every tag name, including the slot names the old `Slot` enum carried.
         *
         * The slots are here rather than dropped because a character asset still binds an ability
         * to one (`abilitySpec(..., tags = listOf("Slot.A"))`), and a name the table does not hold
         * is a boot failure the moment that corpus is wired up.
         */
        public val NAMES: List<String> = listOf(
            STUNNED,
            PHYSICAL, MAGIC, TRUE_DAMAGE,
            DATA_DAMAGE, DATA_KNOCKBACK, DATA_DURATION, DATA_HEAL, DATA_COOLDOWN,
            COST_MANA, COST_HEALTH,
            HINT_HEAL, HINT_DAMAGE, HINT_AOE, HINT_MELEE, HINT_RANGED,
            HINT_TARGET_ENEMY, HINT_TARGET_FRIENDLY, FEARLESS,
            "Slot.A", "Slot.B", "Slot.C", "Slot.D", "Slot.E", "Slot.F", "Slot.G", "Slot.H",
        )

        /** A fresh table. Per game instance, so two games in one JVM share no mutable state. */
        public fun create(): MobaTags = MobaTags(GameplayTagTable.of(NAMES))
    }
}
