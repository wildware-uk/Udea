package dev.wildware.moba.ability

import dev.wildware.udea.gas.AttributeDecl
import dev.wildware.udea.gas.AttributeId
import dev.wildware.udea.gas.AttributeModule
import dev.wildware.udea.gas.AttributeTable
import dev.wildware.udea.gas.value

/**
 * The stats every unit in this game has, and the ids they were assigned.
 *
 * ## Ported from `CharacterAttributeSet`
 *
 * The old set (`example/.../ability/CharacterAttributeSet.kt`) declared eight `Attribute` objects
 * per entity, each with its own `currentValue`, `baseValue` and modifier list. Every unit
 * therefore carried eight objects and the aggregation walked them. Here an entity's stats are two
 * `FloatArray`s indexed by [AttributeId] ([dev.wildware.udea.gas.Attributes]), and this class is
 * the only place that knows which index is which.
 *
 * Two defects came across the port and are fixed rather than reproduced:
 *
 * - `maxHealth` was declared as `attribute("health", initHealth)` - **the same name as `health`**.
 *   A name-keyed lookup could not tell them apart. They are distinct names here, and
 *   `DuplicateAttributeException` makes a repeat of that mistake a boot failure.
 * - `mana`'s ceiling was `value(initMana)`, a snapshot of the constructor argument, so a unit
 *   whose maximum mana was later raised could never fill the new capacity. It is
 *   `value(maxMana)` - the attribute - here, which is what the old `health` line already did.
 *
 * ## Why the table is built twice
 *
 * A ceiling that names another attribute needs that attribute's [AttributeId], and ids are handed
 * out by [AttributeTable] from **sorted names** - so they do not exist until a table does. The
 * first build is over plain declarations and exists only to learn the ids; the second is the real
 * one. [create] then asserts the two agree name-for-name, so if a second [AttributeModule] ever
 * joins this table and shifts the numbering, the build fails loudly instead of clamping health
 * against whatever attribute happens to land on that index.
 */
public class CharacterAttributes private constructor(
    /** Every attribute in this game. */
    public val table: AttributeTable,
) {

    /** Current hit points. Damage and healing write it; zero means dead. */
    public val health: AttributeId = table.idOf(HEALTH)

    /** The ceiling [health] is clamped to. */
    public val maxHealth: AttributeId = table.idOf(MAX_HEALTH)

    /** Spent by abilities that declare a cost. */
    public val mana: AttributeId = table.idOf(MANA)

    /** The ceiling [mana] is clamped to. */
    public val maxMana: AttributeId = table.idOf(MAX_MANA)

    /** Melee damage scales off it, exactly as `UnitMeleeAttack` scaled off `strength`. */
    public val strength: AttributeId = table.idOf(STRENGTH)

    /** Physical mitigation. Declared and read by nothing yet - see the module KDoc. */
    public val armour: AttributeId = table.idOf(ARMOUR)

    /** Magical mitigation. Declared and read by nothing yet. */
    public val magicResist: AttributeId = table.idOf(MAGIC_RESIST)

    /** Health per second restored by `effect/passive_health_regen`. */
    public val healthRegen: AttributeId = table.idOf(HEALTH_REGEN)

    public companion object {

        private const val PREFIX: String = "dev.wildware.moba.Character."

        public const val HEALTH: String = PREFIX + "health"
        public const val MAX_HEALTH: String = PREFIX + "maxHealth"
        public const val MANA: String = PREFIX + "mana"
        public const val MAX_MANA: String = PREFIX + "maxMana"
        public const val STRENGTH: String = PREFIX + "strength"
        public const val ARMOUR: String = PREFIX + "armour"
        public const val MAGIC_RESIST: String = PREFIX + "magicResist"
        public const val HEALTH_REGEN: String = PREFIX + "healthRegen"

        /** Every attribute name, in declaration order. Ids come from the sorted order, not this. */
        public val NAMES: List<String> = listOf(
            HEALTH, MAX_HEALTH, MANA, MAX_MANA, STRENGTH, ARMOUR, MAGIC_RESIST, HEALTH_REGEN,
        )

        /** Builds the table, resolving the two cross-attribute ceilings. See the class KDoc. */
        public fun create(): CharacterAttributes {
            val probe = AttributeTable.of(listOf(Module(NAMES.map { AttributeDecl(it) })))
            val table = AttributeTable.of(listOf(Module(bound(probe))))
            for (name in NAMES) {
                check(table.idOf(name) == probe.idOf(name)) {
                    "attribute ids moved between the probe table and the real one: $name is " +
                        "${table.idOf(name).index} but the ceilings were resolved against " +
                        "${probe.idOf(name).index}. Another AttributeModule has joined this " +
                        "table; resolve the ceilings against the merged table instead."
                }
            }
            return CharacterAttributes(table)
        }

        /** The real declarations, with `health` and `mana` capped by their maxima. */
        private fun bound(ids: AttributeTable): List<AttributeDecl> {
            val maxHealth = ids.idOf(MAX_HEALTH)
            val maxMana = ids.idOf(MAX_MANA)
            return listOf(
                AttributeDecl(HEALTH, defaultBase = 100f, min = value(0f), max = value(maxHealth)),
                AttributeDecl(MAX_HEALTH, defaultBase = 100f, min = value(0f)),
                AttributeDecl(MANA, defaultBase = 0f, min = value(0f), max = value(maxMana)),
                AttributeDecl(MAX_MANA, defaultBase = 0f, min = value(0f)),
                AttributeDecl(STRENGTH, defaultBase = 10f),
                AttributeDecl(ARMOUR, defaultBase = 0f),
                AttributeDecl(MAGIC_RESIST, defaultBase = 0f),
                // `1F` in the old set, which quietly healed every unit in the game whether or not
                // it had the regen effect. Zero here: a unit regenerates because its kind says so.
                AttributeDecl(HEALTH_REGEN, defaultBase = 0f),
            )
        }
    }

    /** This game's contribution to the attribute table. */
    private class Module(private val decls: List<AttributeDecl>) : AttributeModule {
        override val moduleName: String get() = "moba"
        override fun attributes(): List<AttributeDecl> = decls
    }
}
