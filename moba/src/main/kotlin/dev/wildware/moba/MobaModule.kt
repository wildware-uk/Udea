package dev.wildware.moba

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.moba.ability.MobaAbilityModule
import dev.wildware.moba.level.MobaBlueprints
import dev.wildware.moba.level.UnitBattleSystem
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPlacement
import dev.wildware.udea.core.blueprint.blueprintSpawner
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.render.input.IntentSampleSystem
import dev.wildware.udea.render.input.IntentState

/**
 * This game's content: the units, the fight between them, and the spawner that places them.
 *
 * ## What this module is and is not
 *
 * It is a real [UdeaModule], contributed to a real [dev.wildware.udea.core.module.UdeaGameDef],
 * and every entry point in `dev.wildware.moba.entry` builds the same one. That is the property
 * spec 4 asks for: a behaviour that reproduces on the server and not in the agent's instance is
 * a bug in a renderer, because there is only one simulation here.
 *
 * It **was** one component, one blueprint called `grunt` and a system that slid it sideways, with
 * a note saying content was Phase 2's work. The content is here now: `level/test_level` is the
 * old example game's roster - a soldier, a priest, five orcs, ten skeletons and ten soldiers.
 *
 * ## Two modules, one unit
 *
 * This module owns a [MobaAbilityModule] and the game's [MobaBlueprints] are built against it, so
 * a unit the level spawns carries both halves of the game on one entity: [UnitBattleSystem]'s
 * spatial half (who to go for, walking there, which way to face) and `udea-gas`'s combat half
 * (health as an attribute, damage as an effect, cooldowns that rewind, the priest's heal and the
 * soldier's arrow). They were built in parallel by two agents and were, until this wire-up, two
 * rosters that never met: every family in `dev.wildware.moba.ability` was empty in the shipped
 * game because no entity in it had a `Combatant`.
 *
 * Owning the combat module rather than listing it beside this one in `MobaGame.definition` is
 * what makes that unfakeable. `MobaBlueprints` needs the *same* attribute, effect and ability
 * tables the `GasModule` runs, and two modules constructed independently and handed to one
 * definition is four chances to build a game whose units hold ability indices into a table they
 * are not in.
 *
 * ## Where the drift went
 *
 * `DriftSystem` moved every `Position` a quarter of a unit per tick around a 90-unit field, and
 * existed for one reason: an instance had to be *observably* running from outside, and one unit
 * sliding was the smallest thing that made two `/state` reads differ. Units that walk toward
 * enemies and die do that better, and the drift would now be a second, invisible force acting on
 * a fight - so it is deleted rather than kept as a system nothing wants. `MobaSceneTest`'s
 * playfield tests went with it; the level's own layout tests replaced them.
 */
public class MobaModule(
    /** This game's combat. Contributed to the definition by [MobaGame], not by this module. */
    public val combat: MobaAbilityModule,
) : UdeaModule {

    override val name: String get() = "moba"

    /**
     * The four units the level can spawn, built against [combat]'s tables.
     *
     * Published on the context under [MobaBlueprints.KEY], because a scene, an agent's blueprint
     * catalog and a player spawn all need it and none of them holds this module.
     */
    public val blueprints: MobaBlueprints = MobaBlueprints(combat)

    /**
     * The spawner, published on the context so `ctx.blueprints` can find it.
     *
     * Assigned by [MobaGame] between constructing this module and building the definition: a
     * [BlueprintSpawner] needs the `SimBarrier` and the `NetIdIndex`, both of which come off the
     * definition's `core` module, which cannot exist before the module list does.
     */
    public var spawner: BlueprintSpawner? = null

    override fun context(builder: GameContextBuilder) {
        builder.blueprintSpawner(
            checkNotNull(spawner) { "MobaGame wires the spawner before building the definition" },
        )
        builder.service(MobaBlueprints.KEY, blueprints)
    }

    /**
     * Input, movement, the spatial half of the fight, and the animation the fight produces.
     *
     * Death is **not** here any more. `UnitDeathSystem` removed a unit whose `Position.hp` had
     * run out, and `Position.hp` is now a copy that `dev.wildware.moba.ability.DeathSystem`
     * writes from the `health` attribute - so keeping it would have been a second remover reading
     * the first one's mirror, with the loser of that race deciding when a net id is freed. One
     * death path, in the module that owns health.
     */
    override fun simulation(registry: SimRegistry) {
        // `SimPhase.Intent`, and *after* the sampler, so the axis this reads was sampled on this
        // tick rather than on the previous one. Declared rather than left to registration order:
        // `MobaModule` is listed after `InputModule` in `MobaGame.definition` today, and somebody
        // reordering that list would otherwise introduce a one-tick input lag no test names.
        registry.add(
            SimPhase.Intent,
            { ctx -> PlayerControlSystem(ctx[IntentState.KEY], combat.gas.activation) },
        ) { after(IntentSampleSystem::class) }
        registry.add(SimPhase.Movement, { PlayerMovementSystem() })
        registry.add(SimPhase.Gameplay, { UnitBattleSystem() })
        // Both in `Cleanup`, and the order between them is declared rather than left to this
        // file's line order: `CharacterStateSystem` decides which animation a unit is in and
        // `CharacterAnimationSystem` computes which of that animation's notify frames the tick
        // landed on. Run the other way round, a unit that changed what it was doing this tick has
        // its notifies computed against last tick's animation - one frame of wrong sound effect
        // per state change, on every unit, which is exactly the class of bug nobody files.
        //
        // They run after the fighting and the walking so a unit that started swinging on this
        // tick is drawn swinging on this tick. Neither writes anything a gameplay system reads.
        registry.add(SimPhase.Cleanup, { CharacterStateSystem(combat.effects) })
        registry.add(SimPhase.Cleanup, { CharacterAnimationSystem() }) {
            after(CharacterStateSystem::class)
        }
    }
}

/** This game's spatial component is [Position]. */
public object PositionPlacement : SpawnPlacement {

    override fun defaultIfAbsent(world: World, entity: Entity) {
        with(world) {
            if (entity.getOrNull(Position) == null) entity.configure { it += Position() }
        }
    }

    override fun moveTo(world: World, entity: Entity, x: Float, y: Float) {
        with(world) {
            val position = entity[Position]
            position.x = x
            position.y = y
        }
    }
}
