package dev.wildware.moba.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import dev.wildware.moba.Position
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AttributeTable
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.CooldownGroup
import dev.wildware.udea.gas.CooldownSharing
import dev.wildware.udea.gas.GameplayEffects

/**
 * What one kind of unit starts with: its stats, its side and the abilities in its slots.
 *
 * The runtime shape of the `character(...)` scripts under `assets/character`. Those scripts are
 * the authored source and are **packed** - `character` yields a
 * `dev.wildware.udea.assets.Character` - so a loader could build this from the bundle. It is still
 * built in Kotlin because an ability index and an `AttributeId` are interning results a bundle
 * cannot carry; `MobaAuthoredContentTest` compares the halves that *are* data (the roster, the
 * animation roles, the loadout ids) so the stand-in cannot drift from what it stands in for.
 *
 * (The KDoc that stood here named a `MobaUnitAssetParityTest`. There was no such test.)
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
        // `KIND_SLOTS` and not `ABILITY_SLOTS`: the slots above it are the item bar, and a kind
        // that declared three abilities would otherwise be granted its third into the slot
        // `ItemActiveSystem` grants a bought active into - two writers of one slot.
        var slot = 0
        while (slot < kind.abilities.size && slot < KIND_SLOTS) {
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
         * Slots a unit's own kind may fill.
         *
         * Two, because every character in the corpus has a basic attack and at most one special,
         * and because `PlayerControlSystem.SLOT_PRIMARY`/`SLOT_SECONDARY` are the two keys bound
         * to them.
         */
        public const val KIND_SLOTS: Int = 2

        /**
         * How many item actives a champion may hold at once.
         *
         * Two, and it is a game-design number rather than a technical one: a MOBA inventory is six
         * items and a trinket, more of which may declare an active than a player could sensibly
         * bind. `ItemActiveSystem` fills these in inventory-slot order and grants no more.
         */
        public const val ITEM_SLOTS: Int = 2

        /**
         * Slots every unit gets: the kind's own, then [ITEM_SLOTS] for the actives items grant.
         *
         * ## Why the count is the same for a creep and for a champion
         *
         * [dev.wildware.udea.gas.AbilitiesReplicator] refuses to apply a captured
         * [Abilities] of one slot count onto a component with another - it says so in as many
         * words - so a slot count that varied by what an entity turned out to be would be a
         * rewind that failed on the entity whose slot count had changed since the capture. One
         * number for every unit makes that unreachable rather than unlikely.
         *
         * The cost is two [dev.wildware.udea.gas.AbilityInstance]s per creep that nothing will
         * grant, which is what the two-slot version of this KDoc was avoiding when a champion had
         * nothing to put in them. A champion does now: only a champion carries an inventory, so
         * only a champion is granted anything into [ITEM_SLOT_FIRST] and above.
         */
        public const val ABILITY_SLOTS: Int = KIND_SLOTS + ITEM_SLOTS

        /**
         * The first slot an item active is granted into.
         *
         * Item actives sit **above** a kind's own slots so that adding one never moves the slot a
         * key fires: `attack` is slot 0 on every unit in this game whether or not it is carrying
         * anything.
         */
        public const val ITEM_SLOT_FIRST: Int = KIND_SLOTS

        /**
         * Whether [slot] is one of the item-active slots rather than one of a kind's own.
         *
         * A function and not a range literal at every call site, because "which slots are the item
         * bar" is the arithmetic `PlayerControlSystem`, `ItemActiveSystem`, the HUD and
         * [ITEM_COOLDOWN_SHARING] all have to agree on.
         */
        public fun isItemSlot(slot: Int): Boolean = slot in ITEM_SLOT_FIRST until ABILITY_SLOTS

        /**
         * The item bar as one shared cooldown, and every other slot on its own.
         *
         * This is the "shared item-cooldown slot" of issue #166 in one line: firing either item
         * active starts a cooldown both of them wait out, and neither of a champion's own two
         * slots is in the group, so a champion ability and an item active never cool down
         * together. See [CooldownSharing] for why the group belongs to the slot and not to the
         * ability definition - `item/aegis` grants `ability/priest_heal`, which is also the
         * priest's own slot-one ability, and a group on the definition would make the two share.
         */
        public val ITEM_COOLDOWN_SHARING: CooldownSharing = CooldownSharing { slot ->
            if (isItemSlot(slot)) ITEM_COOLDOWN_GROUP else CooldownGroup.NONE
        }

        /** The one group [ITEM_COOLDOWN_SHARING] hands out. */
        private val ITEM_COOLDOWN_GROUP: CooldownGroup = CooldownGroup(0)
    }
}

/**
 * The arrow the soldier fires.
 *
 * Ported from `assets/blueprint/projectiles.udea.kts`, whose ancestor's three `onHitEffects`
 * records are the three fields on [Projectile]. It carries no `Attributes` and no `Combatant`, so it is not a
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
            // The picture the port dropped. `blueprint/arrow.udea.kts` carried
            // `spriteRenderer(texture = loadSprite("/sprites/arrow/arrow.png", .1F))`; this
            // blueprint carried nothing, and `sprites/arrow/arrow.png` was not in this module at
            // all - so a soldier's arrow crossed forty world units and took ten health off a
            // skeleton with nothing drawn between them.
            //
            // `facesMotion`, which the old one did not do: the arrow was a Box2D kinematic body
            // whose angle nothing ever set, so the sprite pointed right however the shot was
            // aimed. `SpriteView.FOREVER` because an arrow is despawned by `ProjectileSystem` on
            // contact or when its `lifeTicks` run out, not by an animation ending.
            entity += dev.wildware.moba.SpriteView(
                animation = ANIMATION,
                facesMotion = true,
            )
        }
    }

    override fun toString(): String = "ArrowBlueprint"

    public companion object {

        /** The authored one-frame `spriteAnimation` an arrow wears. */
        public val ANIMATION: dev.wildware.udea.assets.AssetId =
            dev.wildware.udea.assets.AssetId("sprites/arrow/arrow")

        /** `Data.Duration to 0.2F` on the arrow's stun effect, at 60Hz. */
        public const val STUN_TICKS: Int = 12

        /** `Data.Knockback to 0.2F`, as world units per tick. */
        public const val KNOCKBACK: Float = 0.08f * MobaScale.WORLD
    }
}
