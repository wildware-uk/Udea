package dev.wildware.moba.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import dev.wildware.moba.Position
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AttributeTable
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects

/**
 * What one kind of unit starts with: its stats, its side and the abilities in its slots.
 *
 * The runtime shape of the `character(...)` scripts under `src/main/assets/character`. Those
 * scripts are the authored source and this is a hand-written stand-in for what the loader will
 * produce: `character` is `AssetKind.Unpublishable`, so the packer cannot publish one and nothing
 * loads them at boot. `MobaUnitAssetParityTest` diffs the numbers here against the numbers there,
 * so the stand-in cannot drift away from the corpus it stands in for.
 */
public class UnitKind(
    /** The character asset's name: `soldier`, `priest`, `orc`, `orc_elite`, `skeleton`, `wizard`. */
    public val name: String,
    /** Which side it fights for. */
    public val team: Int,
    /** Starting and maximum health. */
    public val health: Float,
    /** Starting and maximum mana. */
    public val mana: Float = 0f,
    /** Melee damage scales off it. */
    public val strength: Float = 10f,
    /** Physical mitigation. Declared by the corpus; nothing reads it yet. */
    public val armour: Float = 0f,
    /** Magical mitigation. Declared by the corpus; nothing reads it yet. */
    public val magicResist: Float = 0f,
    /** Health per second, if anything ever applies `ability/passive_health_regen`. */
    public val healthRegen: Float = 0f,
    /**
     * Ability table indices, by slot. Slot 0 is the basic attack, slot 1 the special - which is
     * `Slot.A` and `Slot.B` in the character scripts.
     */
    public val abilities: IntArray,
) {
    override fun toString(): String = "UnitKind($name, team=$team, health=$health)"
}

/**
 * Spawns one [UnitKind].
 *
 * One blueprint object per kind, built once by [MobaAbilityModule] and registered so an agent can
 * `world.spawn` it by name. [configure] writes fields into freshly constructed components and
 * allocates exactly what the entity keeps - no per-spawn map, no reflection, no asset lookup.
 */
public class UnitBlueprint(
    /** The kind this spawns. */
    public val kind: UnitKind,
    private val attributeTable: AttributeTable,
    private val ids: CharacterAttributes,
) : Blueprint {

    override val id: BlueprintId = BlueprintId("unit/${kind.name}")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) { entity += Position(hp = kind.health) }
        dress(context, entity)
    }

    /**
     * Adds everything that makes [entity] a combatant, and **not** its [Position].
     *
     * Split out of [configure] so the level's own blueprint - which owns the entity's `Position`,
     * its `GameUnit` and the art it wears - can put this game's combat on the same entity instead
     * of beside it. Before this seam existed the two halves were two separate rosters: the level
     * spawned twenty-seven units with a health float and a sprite, `MobaAbilityModule` registered
     * a full ability system, and no entity in a shipped process ever carried both - so every
     * family in this package was empty in the running game and every ability was unreachable.
     *
     * `Position` is deliberately the caller's, because a unit's starting health is
     * [UnitKind.health] here and its spawn coordinates come from the level: two writers of one
     * component is how a priest ends up spawning with a soldier's hit points.
     */
    public fun dress(context: EntityCreateContext, entity: Entity) {
        val attributes = Attributes(attributeTable)
        attributes.setBase(ids.maxHealth, kind.health)
        attributes.setBase(ids.health, kind.health)
        attributes.setBase(ids.maxMana, kind.mana)
        attributes.setBase(ids.mana, kind.mana)
        attributes.setBase(ids.strength, kind.strength)
        attributes.setBase(ids.armour, kind.armour)
        attributes.setBase(ids.magicResist, kind.magicResist)
        attributes.setBase(ids.healthRegen, kind.healthRegen)
        // `current` is derived every tick from `base`, but a unit is read - by the autopilot, by
        // a test, by `world.query` - before the first `AttributeSystem` pass runs on it. Seeding
        // it means a unit spawned this tick is not momentarily a corpse with zero health.
        System.arraycopy(attributes.base, 0, attributes.current, 0, attributes.base.size)

        val granted = Abilities(ABILITY_SLOTS)
        var slot = 0
        while (slot < kind.abilities.size && slot < ABILITY_SLOTS) {
            granted.grant(slot, kind.abilities[slot])
            slot++
        }

        with(context) {
            entity += Combatant(kind.team)
            entity += Motion(damping = Motion.UNIT_DAMPING)
            entity += attributes
            entity += GameplayEffects()
            entity += granted
        }
    }

    override fun toString(): String = "UnitBlueprint(${id.value})"

    public companion object {
        /**
         * Slots every unit gets.
         *
         * Two, because every character in the corpus has a basic attack and at most one special.
         * `Abilities.DEFAULT_SLOTS` is six - a champion's four plus two item actives - and six
         * empty slots per unit is six instances per unit that nothing will ever grant.
         */
        public const val ABILITY_SLOTS: Int = 2
    }
}

/**
 * The arrow the soldier fires.
 *
 * Ported from `src/main/assets/blueprint/arrow.udea.kts`, whose three `onHitEffects` records are
 * the three fields on [Projectile]. It carries no `Attributes` and no `Combatant`, so it is not a
 * unit: [CombatIndex] never sees it, nothing can target it, and it cannot be healed - which the
 * old arrow could be, because it carried a `Team` component and the friendly-unit query asked
 * only for a matching team.
 */
public class ArrowBlueprint : Blueprint {

    override val id: BlueprintId = BlueprintId("blueprint/arrow")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) {
            entity += Position()
            entity += Motion(damping = Motion.PROJECTILE_DAMPING)
            entity += Projectile(stunTicks = STUN_TICKS, knockback = KNOCKBACK)
        }
    }

    override fun toString(): String = "ArrowBlueprint"

    public companion object {

        /** `Data.Duration to 0.2F` on the arrow's stun effect, at 60Hz. */
        public const val STUN_TICKS: Int = 12

        /** `Data.Knockback to 0.2F`, as world units per tick. */
        public const val KNOCKBACK: Float = 0.08f * MobaScale.WORLD
    }
}
