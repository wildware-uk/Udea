package dev.wildware.moba.ability

import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.blueprintSpawner
import dev.wildware.udea.core.blueprint.blueprints
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.gas.AbilityAuthority
import dev.wildware.udea.gas.AbilityExecRegistry
import dev.wildware.udea.gas.AbilitySystem
import dev.wildware.udea.gas.GasModule

/**
 * This game's combat: attributes, effects, abilities, the units that have them, and the systems
 * that make them do something.
 *
 * ## One module, not two
 *
 * It owns a [GasModule] and forwards [context] and [simulation] to it, so a game adds combat by
 * adding **this** to its module list and gets the engine half wired correctly by construction.
 * The alternative - asking every game to list `GasModule(tables...)` and a content module beside
 * it, in that order, with the same tables passed to both - is four chances to build a game whose
 * abilities reference an effect table its effects are not in.
 *
 * ## What is wired, in the order it runs
 *
 * | Phase | System | Why there |
 * |---|---|---|
 * | `PreSimulation` | [CombatIndex] | The per-tick unit view every ability queries. |
 * | `Ability` | [AbilityAutopilotSystem] | Starts activations, before the in-flight ones tick. |
 * | `Ability` | `AbilitySystem` | Runs in-flight activations (from `GasModule`). |
 * | `Attribute` | `AttributeSystem` | Rebuilds `current` from base plus modifiers. |
 * | `Movement` | [CombatMotionSystem] | Knockbacks and arrows actually move. |
 * | `PostPhysics` | [ProjectileSystem] | Arrows hit what they have reached. |
 * | `Gameplay` | [DeathSystem] | Publishes health to `Position.hp`; removes the dead. |
 * | `Cleanup` | [DeathTagSystem] | Puts `Debuffs.Dead` on a corpse, so an ability can be blocked by it. |
 * | `Cleanup` | `GasCueForwardSystem` | Drains GAS cues into `GameContext.cues`. |
 *
 * ## Stated plainly
 *
 * - Nothing renders a unit, plays a cue or draws a healthbar. Cues are emitted and forwarded; the
 *   presentation half of this wave is somebody else's file.
 * - `magicResist` is declared, spawned and read by no damage formula, and no item raises it. The
 *   old game did not have one either: `UnitMeleeAttack` applied `-strength` with a
 *   `Damage.Physical` tag and nothing consumed the tag. `armour` is in the same position as a
 *   *formula*, but it is no longer inert as a number: `moba.item.ItemPassiveSystem` moves it, so a
 *   champion who buys `item/bulwark` can watch it change.
 */
public class MobaAbilityModule(
    /**
     * Whether [AbilityAutopilotSystem] is registered.
     *
     * `true` makes units fight on their own, which is what makes an instance worth looking at.
     * `false` leaves activation entirely to whatever drives it - the AI port, a player controller,
     * an agent tool - without this module and that one both firing the same slot.
     */
    private val autopilot: Boolean = true,
    /** Which entities this simulation may activate abilities on. */
    authority: AbilityAuthority = AbilityAuthority.All,
) : UdeaModule {

    override val name: String get() = "moba-combat"

    /** The tag vocabulary. */
    public val tags: MobaTags = MobaTags.create()

    /** The attribute table and this game's ids into it. */
    public val attributes: CharacterAttributes = CharacterAttributes.create()

    /** Every gameplay effect. */
    public val effects: MobaEffects = MobaEffects.create(tags, attributes)

    /** How an exec reaches the units around it. Bound by [CombatIndex] when the world builds it. */
    public val combat: CombatWorldRef = CombatWorldRef()

    /** The four things an ability does to somebody else. */
    public val rules: CombatRules = CombatRules(tags, attributes, effects, combat)

    /** The execs, in a registry that assigns each a stable id. */
    public val execs: AbilityExecRegistry = AbilityExecRegistry.of(
        listOf(
            MeleeAttackExec(rules),
            OrcSpinExec(rules),
            PriestHealExec(rules),
            FireArrowExec(rules),
        ),
    )

    /** Every ability, and what each wants to be pointed at. */
    public val abilities: MobaAbilities = MobaAbilities.create(tags, attributes, effects, execs, rules)

    /** The arrow. */
    public val arrow: ArrowBlueprint = ArrowBlueprint()

    /** Every unit kind, ported from the `character(...)` scripts. */
    public val units: List<UnitBlueprint> = MobaUnits.kinds(abilities).map {
        UnitBlueprint(it, attributes.table, attributes)
    }

    /** Everything this module can spawn, for a catalog an agent reads. */
    public val blueprints: List<Blueprint> = units + arrow

    /** [UnitBlueprint] for [name], or `null`. */
    public fun unit(name: String): UnitBlueprint? = units.firstOrNull { it.kind.name == name }

    /**
     * The engine half.
     *
     * Constructed here so the tables it is given are the same objects this module's content built.
     */
    public val gas: GasModule = GasModule(
        attributes = attributes.table,
        effects = effects.table,
        abilities = abilities.table,
        execs = execs,
        authority = authority,
        // The item bar cools down as one, independently of a champion's own two slots. Declared
        // here rather than in the item module because the slot layout is a property of the
        // ability bar this module dresses every unit with, and `GasModule` - which owns the one
        // `AbilityActivation` in the world - is constructed here. A definition assembled without
        // the item module still gets it, and it then applies to two slots nothing grants into.
        sharing = UnitBlueprint.ITEM_COOLDOWN_SHARING,
    )

    /**
     * The spawner this game uses, when this module is the one that owns it.
     *
     * `null` means somebody else registered one (`MobaModule` does), and this module reads it off
     * the context instead. Set it when assembling a definition that has no other spawner - a test,
     * or a game whose only content is combat.
     */
    public var spawner: BlueprintSpawner? = null

    override fun context(builder: GameContextBuilder) {
        gas.context(builder)
        spawner?.let { builder.blueprintSpawner(it) }
    }

    override fun simulation(registry: SimRegistry) {
        gas.simulation(registry)
        registry.add(SimPhase.PreSimulation, { ctx ->
            CombatIndex(
                netIds = ctx[CoreModule.NET_IDS],
                spawner = ctx.blueprints,
                arrow = arrow,
                health = attributes.health,
            ).also(combat::bind)
        })
        if (autopilot) {
            registry.add(
                SimPhase.Ability,
                { ctx ->
                    AbilityAutopilotSystem(
                        activation = gas.activation,
                        targeting = abilities.targeting,
                        combat = combat,
                        rules = rules,
                        netIds = ctx[CoreModule.NET_IDS],
                    )
                },
                // Before the system that advances in-flight activations, so an ability started
                // this tick gets its first `onTick` this tick rather than next.
                { before(AbilitySystem::class) },
            )
        }
        registry.add(SimPhase.Movement, { CombatMotionSystem() })
        registry.add(SimPhase.PostPhysics, { ctx ->
            ProjectileSystem(
                combat = combat,
                rules = rules,
                applier = gas.applier,
                cues = gas.cues,
                netIds = ctx[CoreModule.NET_IDS],
            )
        })
        registry.add(SimPhase.Gameplay, { ctx ->
            DeathSystem(rules = rules, cues = gas.cues, netIds = ctx[CoreModule.NET_IDS])
        })
        // `Cleanup`, which is after `Gameplay` absolutely rather than by a declared edge, so the
        // unit `DeathSystem` retired on this tick carries `Debuffs.Dead` on this tick. A phase
        // later would be a one-tick window in which a key press still cast out of a corpse.
        registry.add(SimPhase.Cleanup, { DeathTagSystem(effects.dead, gas.applier) })
    }

    override fun toString(): String = "MobaAbilityModule(${units.size} unit kinds, autopilot=$autopilot)"
}

/**
 * The six characters the old game shipped, as [UnitKind]s.
 *
 * Every number is the one in the matching `character(...)` script under `assets/character`; where
 * a script omits a stat, the attribute's own default applies. `strength` is absent from four of
 * the six scripts, so those units deal the default 10 - which is what the old
 * `CharacterAttributeSet(initStrength = 10F)` gave them.
 */
public object MobaUnits {

    /** Every kind, with its ability slots resolved against [abilities]. */
    public fun kinds(abilities: MobaAbilities): List<UnitKind> = listOf(
        UnitKind(
            name = "soldier",
            team = Teams.SOLDIER,
            health = 100f,
            armour = 50f,
            abilities = intArrayOf(abilities.melee, abilities.fireArrow),
        ),
        UnitKind(
            name = "priest",
            team = Teams.SOLDIER,
            health = 50f,
            mana = 100f,
            healthRegen = 2f,
            abilities = intArrayOf(abilities.melee, abilities.priestHeal),
        ),
        UnitKind(
            name = "wizard",
            team = Teams.SOLDIER,
            health = 50f,
            mana = 100f,
            abilities = intArrayOf(abilities.melee),
        ),
        UnitKind(
            name = "orc",
            team = Teams.ORC,
            health = 150f,
            magicResist = 20f,
            abilities = intArrayOf(abilities.melee),
        ),
        UnitKind(
            name = "orc_elite",
            team = Teams.ORC,
            health = 500f,
            strength = 20f,
            armour = 20f,
            magicResist = 20f,
            abilities = intArrayOf(abilities.melee, abilities.orcSpin),
        ),
        UnitKind(
            name = "skeleton",
            team = Teams.UNDEAD,
            health = 50f,
            magicResist = 20f,
            abilities = intArrayOf(abilities.melee),
        ),
    )
}
