package dev.wildware.moba.level

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import dev.wildware.moba.CharacterRoster
import dev.wildware.moba.CharacterView
import dev.wildware.moba.MobaCharacters
import dev.wildware.moba.Position
import dev.wildware.moba.ability.MobaAbilityModule
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.serviceKey

/**
 * One spawnable unit: a [UnitKind], a [Team], and the art it wears.
 *
 * ## Why the code blueprint and the authored blueprint are both here
 *
 * There are two `Blueprint` types in this build and they are not the same thing:
 * [dev.wildware.udea.core.blueprint.Blueprint] is code that configures a Fleks entity, and
 * [dev.wildware.udea.assets.Blueprint] is packed data naming component *type names* and their
 * fields. Nothing in the engine turns the second into the first yet - that needs a
 * component-name-to-`ComponentType` registry, which is a piece of engine and not a piece of a
 * level.
 *
 * So the roster is authored (`assets/level/test_level.udea.kts` says which unit stands where and
 * `assets/blueprint/units.udea.kts` declares the ids it names) and the *construction* is code. [MobaBlueprints.byAssetId] is the seam between the
 * two, and it is deliberately strict: an authored entity pointing at a blueprint id no code
 * blueprint answers to fails the scene swap loudly rather than spawning nothing and leaving the
 * level short of a unit nobody counted.
 */
public class UnitBlueprint(
    /** Which unit this spawns: how fast it walks, how close it closes, which art it wears. */
    public val kind: UnitKind,
    /** Which side it spawns on. */
    public val team: Int,
    /** The authored asset this answers to, e.g. `blueprint/orc`. */
    public val assetId: AssetId,
    /** Where this unit's combat comes from. */
    private val combat: MobaAbilityModule,
    /** Which [CharacterRoster] entry it wears, resolved once by [MobaBlueprints]. */
    private val characterIndex: Int,
) : Blueprint {

    override val id: BlueprintId = BlueprintId(assetId.value.substringAfterLast('/'))

    /** This unit's combat half: its attributes, its effect slots and its ability loadout. */
    public val combatKind: dev.wildware.moba.ability.UnitBlueprint =
        requireNotNull(combat.unit(kind.character)) {
            "`${assetId.value}` wears the character '${kind.character}', and " +
                "`MobaUnits.kinds` declares no unit by that name; it declares " +
                combat.units.joinToString { it.kind.name }
        }

    /**
     * Builds the **whole** unit: where it is, what it is, what it can do and what it looks like.
     *
     * ## The line this class exists for
     *
     * `combatKind.dress(...)` is the integration. Before it, `:moba` carried two rosters that
     * never met: this file spawned twenty-seven entities with a `Position`, a `GameUnit` and a
     * sprite, while `MobaAbilityModule` registered an ability system, four execs and a projectile
     * pipeline whose every family required a `Combatant` no shipped entity had. Both halves were
     * green in their own tests and the running game had no abilities in it at all - the priest
     * could not heal, the soldier could not shoot, and `UnitBattleSystem` subtracted a float to
     * stand in for all of it.
     *
     * The order matters and is not arbitrary. [Position] is written here, once, carrying
     * [dev.wildware.moba.ability.UnitKind.health] - so the health the healthbar draws, the health
     * `DeathSystem` mirrors and the health the GAS `health` attribute starts at are the same
     * number by construction rather than by two files agreeing. `dress` then adds the combat
     * components and deliberately does not touch `Position` for exactly that reason.
     *
     * [GameUnit] stays, and is not folded into `Combatant`: it is the `@Replicated` component the
     * snapshot ring records and `world.query_entities with=GameUnit where team=0` filters on, and
     * `Combatant` is neither. They carry the same team id, which
     * [dev.wildware.moba.MobaIntegrationTest] pins rather than leaves to a reader.
     *
     * [CharacterView] carries an index into the [CharacterRoster], resolved at construction from
     * the packed graph - so the art a unit wears is decided by what `assets/character/` declares
     * and this file cannot disagree with it.
     */
    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) {
            entity += Position(hp = combatKind.kind.health)
            entity += GameUnit(team = team, kind = kind.id)
            entity += CharacterView(character = characterIndex)
        }
        combatKind.dress(context, entity)
    }

    override fun toString(): String = "UnitBlueprint(${assetId.value}, ${kind.name}, team=$team)"
}

/**
 * Every unit the level can spawn, and the lookup from an authored id to the code that builds it.
 *
 * The ids are the ones `assets/blueprint/units.udea.kts` declares. Renaming one there without
 * renaming it here turns the scene swap red on the next boot with a message naming both sides,
 * which is the whole reason [byAssetId] refuses rather than skips.
 */
public class MobaBlueprints(
    /** The combat module these units draw their attributes, effects and abilities from. */
    private val combat: MobaAbilityModule,
) {

    /** The player's soldier and the ten with it. `blueprint/soldier`. */
    public val soldier: UnitBlueprint = build(UnitKind.Soldier, Team.SOLDIER)

    /** `blueprint/priest`. On the soldiers' side, as it was in the old level. */
    public val priest: UnitBlueprint = build(UnitKind.Priest, Team.SOLDIER)

    /** `blueprint/orc`. */
    public val orc: UnitBlueprint = build(UnitKind.Orc, Team.ORC)

    /** `blueprint/skeleton`. */
    public val skeleton: UnitBlueprint = build(UnitKind.Skeleton, Team.UNDEAD)

    /** All four, in declaration order. What the agent's blueprint catalog is built from. */
    public val all: List<UnitBlueprint> = listOf(soldier, priest, orc, skeleton)

    private val byId: Map<String, UnitBlueprint> = all.associateBy { it.assetId.value }

    private fun build(kind: UnitKind, team: Int): UnitBlueprint = UnitBlueprint(
        kind = kind,
        team = team,
        assetId = AssetId("blueprint/${kind.character}"),
        combat = combat,
        characterIndex = characterIndexOf(kind),
    )

    /**
     * The code blueprint for an authored [id].
     *
     * @throws IllegalArgumentException when nothing answers to [id]. Deliberate: the alternative
     *   is a level that quietly spawns 26 of its 27 entities, and the missing one is found as a
     *   balance mystery rather than as a broken reference.
     */
    public fun byAssetId(id: AssetId?): UnitBlueprint {
        val value = requireNotNull(id) {
            "a level entity names no blueprint; every entity in `level/test_level` must, because " +
                "this game builds units from code blueprints keyed by the authored id"
        }.value
        return requireNotNull(byId[value]) {
            "no code blueprint answers to the authored id '$value'; this game knows " +
                byId.keys.joinToString()
        }
    }

    /**
     * Which [CharacterRoster] entry [kind] wears, as the index `CharacterView` stores.
     *
     * By name, because that is the one key all three halves share: a roster entry is named by its
     * `character/<name>_animation_set` id, an authored blueprint is `blueprint/<name>`, and a
     * combat kind is `MobaUnits.kinds`' `<name>`. The level, the art tree and the ability table
     * spell it the same way or this refuses.
     *
     * @throws IllegalStateException when the roster has no such character. Loud, because the
     *   alternative - index 0 - would silently dress every orc as whatever sorted first, and a
     *   unit wearing the wrong art reads as an authoring mistake rather than a lookup failure.
     */
    public fun characterIndexOf(kind: UnitKind): Int {
        val roster: CharacterRoster = MobaCharacters.roster
        val index = roster.indexOf(kind.character)
        check(index >= 0) {
            "the character roster has no '${kind.character}'; it holds " +
                roster.entries.joinToString { it.name }.ifEmpty { "nothing" } +
                ". A character is a `spriteAnimationSet` under `assets/character/`, and this " +
                "blueprint names one that tree does not declare."
        }
        return index
    }

    override fun toString(): String = "MobaBlueprints(${all.joinToString { it.id.value }})"

    public companion object {

        /**
         * How a scene, a spawn call or an agent toolset reaches this game's unit blueprints.
         *
         * A [dev.wildware.udea.core.ServiceKey] and not the `object` this used to be, because the
         * blueprints now hold the [MobaAbilityModule] whose attribute, effect and ability tables
         * their units are built against - and those tables belong to **one** `UdeaGameDef`.
         * `MobaGameTest` asserts every definition is a fresh world; a process-wide singleton
         * holding the first definition's tables would have handed the second definition's units
         * ability indices into a table they are not in, which is the quiet kind of cross-talk that
         * shows up as one test failing only when another ran first.
         */
        public val KEY: dev.wildware.udea.core.ServiceKey<MobaBlueprints> =
            serviceKey("moba.blueprints")
    }
}
