package dev.wildware.moba.lane

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import dev.wildware.moba.CharacterStateSystem
import dev.wildware.moba.Position
import dev.wildware.moba.ability.MobaAbilityModule
import dev.wildware.moba.level.Team
import dev.wildware.moba.level.UnitBattleSystem
import dev.wildware.moba.match.RespawnSystem
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.blueprint.SpawnOverrides
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.serviceKey
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.gas.GasCueForwardSystem

/**
 * The lane, as a module: creeps, towers, gold, experience and levels.
 *
 * ## Why this is a module of its own
 *
 * `MobaModule` owns what a unit is, `MatchModule` owns what a session is, and this owns what a
 * *lane* is. They change for different reasons and, more usefully, they come apart: a definition
 * assembled without this one is the three-faction brawl this game was before, which is exactly
 * what a combat unit test wants and exactly what a player does not. Every test in this package
 * that needs the brawl and not the lane gets it by leaving this out of the list.
 *
 * ## Wiring
 *
 * `MobaGame.definition()` is what puts this in the list, and it must be handed the **same**
 * [MobaAbilityModule] the units were dressed with. A tower applies `ability/damage` through that
 * module's `EffectApplier` against that module's effect table, and an index into a second table
 * would apply whatever effect happened to sit at the same position - a tower that healed what it
 * shot at, arrived at silently. That is why the module is a constructor parameter and not a
 * `MobaAbilityModule()` call in this file; `MatchModule` takes its attribute table for the same
 * reason and states it at the same length.
 *
 * ## The order the seven systems run in, and which edges are load-bearing
 *
 * | phase | system | why there |
 * |---|---|---|
 * | `Gameplay` | [LaneSystem] | opens the lane, places towers, sends waves |
 * | `Gameplay` | [LaneMarchSystem] | `after(UnitBattleSystem)`: it reads this tick's target |
 * | `Gameplay` | [TowerSystem] | towers acquire and fire |
 * | `Gameplay` | [ChampionSystem] | grants the wallet the payout needs |
 * | `Cleanup` | [LastHitSystem] | `before(GasCueForwardSystem)`: after it, the queue is empty |
 * | `Cleanup` | [BountySystem] | `after(LastHitSystem)`: it reads what that just wrote |
 * | `Cleanup` | [ChampionRespawnSystem] | `after(RespawnSystem)`, `before(CharacterStateSystem)` |
 * | `Cleanup` | [LanePublishSystem] | last, so the mirror holds the end of the tick |
 *
 * Two of those edges are the whole correctness of the wave and neither is cosmetic:
 *
 * - **`LaneMarchSystem after UnitBattleSystem`.** The march moves a creep only when
 *   `GameUnit.targetRaw` is `NetId.NONE`, which is `UnitBattleSystem`'s way of saying it found
 *   nothing to fight and therefore did not move this unit. Run the other way round and the value
 *   read is last tick's, so a creep that acquired a target this tick marches *and* closes in the
 *   same tick, at the sum of two speeds, away from the enemy it just found.
 * - **`LastHitSystem before GasCueForwardSystem`.** `GasCueForwardSystem` drains the GAS cue
 *   queue. A reader after it reads an empty queue, so every killing blow in the game would be
 *   unattributed and every creep would pay nobody - a bug that presents as "gold does not work"
 *   with nothing red anywhere.
 *
 * `TowerSystem` is deliberately **not** constrained against `DeathSystem`. Both are in
 * `SimPhase.Gameplay`, and `SimRegistry` falls back to registration order inside a phase, which
 * puts this module's systems after `MobaAbilityModule`'s - so a tower's killing blow is seen by
 * `DeathSystem` on the *next* tick rather than on this one. That is a one-tick delay on a corpse
 * and it is correct either way; what would not be correct is asserting an ordering the registry
 * does not guarantee, so it is stated here rather than pinned by an edge that reads as a promise.
 */
public class LaneModule(
    /**
     * The combat module this game's units are dressed against.
     *
     * `MobaAbilityModule`, and the same instance `MobaModule` holds. See the class KDoc.
     */
    private val combat: MobaAbilityModule,
) : UdeaModule {

    override val name: String get() = "moba-lane"

    /**
     * The read mirror, published on the context.
     *
     * One object, constructed here, so the system that writes it and the HUD that reads it cannot
     * be looking at two different lanes. The authority stays in [LaneState] and [Wallet] on
     * entities; see [LaneService] for why a mirror exists at all.
     */
    public val service: LaneService = LaneService()

    override fun context(builder: GameContextBuilder) {
        builder.service(LaneService.KEY, service)
    }

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Gameplay, { LaneSystem() })
        registry.add(SimPhase.Gameplay, { LaneMarchSystem() }) {
            after(UnitBattleSystem::class)
        }
        registry.add(
            SimPhase.Gameplay,
            { TowerSystem(effects = combat.effects, tags = combat.tags, applier = combat.gas.applier) },
        )
        registry.add(SimPhase.Gameplay, { ChampionSystem() })
        registry.add(SimPhase.Cleanup, { LastHitSystem(combat.gas.cues) }) {
            before(GasCueForwardSystem::class)
        }
        registry.add(
            SimPhase.Cleanup,
            {
                BountySystem(
                    health = combat.attributes.health,
                    maxHealth = combat.attributes.maxHealth,
                    strength = combat.attributes.strength,
                )
            },
        ) {
            after(LastHitSystem::class)
        }
        // The respawn scaling reads the tick `RespawnSystem` just wrote, so it must run after
        // it; and it must run before `CharacterStateSystem`, so a champion revived on this tick
        // has its pose derived from health that has already been restored. Those are the two
        // edges `MatchModule` declares around `RespawnSystem` itself, restated here because a
        // phase alone orders nothing inside itself and this system sits between the two.
        registry.add(SimPhase.Cleanup, { ChampionRespawnSystem() }) {
            after(RespawnSystem::class)
            before(CharacterStateSystem::class)
        }
        // The mirror, last, so what it publishes is the state at the end of the tick rather
        // than the state halfway through it.
        registry.add(SimPhase.Cleanup, { LanePublishSystem(service) }) {
            after(BountySystem::class)
        }
    }

    override fun toString(): String = "LaneModule($service)"

    /**
     * The snapshot registry entries for this package's five components.
     *
     * Handed to `MobaGame.componentRegistry` rather than written out there, so that adding a
     * component to a lane is one edit in the package that owns it. The `FieldKind` list beside
     * each replicator is a claim about column types that no generator emits yet, and it must
     * agree with the replicator **field for field in the replicator's own order, which is
     * alphabetical by field name and not declaration order**:
     *
     * | component | fields, in index order |
     * |---|---|
     * | `LaneCreep` | goldBounty, heading, paid, waveNumber, waypoint, xpBounty |
     * | `LaneState` | creepsAlive, nextWaveTick, startedTick, towersPlaced, waveNumber |
     * | `LastHit` | attackerRaw, tick |
     * | `Tower` | readyTick, shots, targetRaw, team |
     * | `Wallet` | gold, lastHits, level, xp |
     *
     * `ComponentSchema.of` refuses a list whose length disagrees with `fieldNames`, so a field
     * added to one of these fails here rather than silently shifting a column. A *kind* typed
     * wrong at the right length is caught by nothing except a round trip, which is why the table
     * is written out rather than left implied - and why `LaneRewindTest` does the round trip.
     */
    public companion object {

        /** @see LaneModule.Companion */
        public fun snapshotTypes(): List<ReplicatedComponentType<*>> = listOf(
            fleksComponentType(
                LaneCreepReplicator,
                ComponentSchema.of(
                    LaneCreepReplicator,
                    "LaneCreep",
                    listOf(
                        FieldKind.Int,
                        FieldKind.Int,
                        FieldKind.Bool,
                        FieldKind.Int,
                        FieldKind.Int,
                        FieldKind.Int,
                    ),
                ),
                LaneCreep,
            ) { LaneCreep() },
            fleksComponentType(
                LaneStateReplicator,
                ComponentSchema.of(
                    LaneStateReplicator,
                    "LaneState",
                    listOf(
                        FieldKind.Int,
                        FieldKind.Long,
                        FieldKind.Long,
                        FieldKind.Bool,
                        FieldKind.Int,
                    ),
                ),
                LaneState,
            ) { LaneState() },
            fleksComponentType(
                LastHitReplicator,
                ComponentSchema.of(
                    LastHitReplicator,
                    "LastHit",
                    listOf(FieldKind.Int, FieldKind.Long),
                ),
                LastHit,
            ) { LastHit() },
            fleksComponentType(
                TowerReplicator,
                ComponentSchema.of(
                    TowerReplicator,
                    "Tower",
                    listOf(FieldKind.Long, FieldKind.Int, FieldKind.Int, FieldKind.Int),
                ),
                Tower,
            ) { Tower() },
            fleksComponentType(
                WalletReplicator,
                ComponentSchema.of(
                    WalletReplicator,
                    "Wallet",
                    listOf(FieldKind.Int, FieldKind.Int, FieldKind.Int, FieldKind.Int),
                ),
                Wallet,
            ) { Wallet() },
        )
    }
}

/**
 * The lane, readable without touching the world.
 *
 * ## Why a mirror exists at all
 *
 * The same argument `MatchService` makes: the authority is a component on an entity, because that
 * is the only kind of state a `time.rewind` restores, and a component on an entity is not
 * something a renderer, a HUD or a test can read without a family lookup and a `with(world)`. So
 * the writing system publishes a copy once a tick and everything that only wants to *look* reads
 * this.
 *
 * It is deliberately a snapshot of numbers and not a handle on the entity: a reader that held the
 * entity would be holding a reference across a scene swap that destroys it.
 */
public class LaneService {

    /** Whether a lane exists in the world at all. `false` in a definition built without this. */
    public var open: Boolean = false
        private set

    /** Waves sent so far, one-based once the first has gone out. */
    public var waveNumber: Int = 0
        private set

    /** The tick the next wave spawns on. */
    public var nextWaveTick: Long = 0L
        private set

    /** Creeps walking right now, both sides. */
    public var creepsAlive: Int = 0
        private set

    /** The champion's gold, or zero when there is no champion. */
    public var gold: Int = 0
        private set

    /** The champion's level. */
    public var level: Int = 1
        private set

    /** Creeps the champion landed the killing blow on. */
    public var lastHits: Int = 0
        private set

    /** Called once a tick by [LanePublishSystem]. Not part of the simulation. */
    public fun publish(state: LaneState) {
        open = true
        waveNumber = state.waveNumber
        nextWaveTick = state.nextWaveTick
        creepsAlive = state.creepsAlive
    }

    /** Called once a tick with the champion's wallet, or with nothing when it is dead. */
    public fun publish(wallet: Wallet?) {
        gold = wallet?.gold ?: gold
        level = wallet?.level ?: level
        lastHits = wallet?.lastHits ?: lastHits
    }

    override fun toString(): String =
        "LaneService(wave=$waveNumber, creeps=$creepsAlive, gold=$gold, level=$level)"

    public companion object {

        /** How a HUD, a renderer or a test reaches the lane. */
        public val KEY: ServiceKey<LaneService> = serviceKey("moba.lane")
    }
}

/**
 * A tower: a [Position] and a [Tower], and nothing else.
 *
 * The team is applied by a [SpawnOverrides] rather than by a blueprint per side, because the two
 * towers differ in exactly one integer and two blueprint objects would be two places for that
 * integer to be wrong.
 *
 * See [Tower] for the honest statement of what a tower in this wave is not: it has no `Combatant`,
 * so nothing can target it and nothing can destroy it.
 */
public object TowerBlueprint : Blueprint {

    override val id: BlueprintId = BlueprintId("lane/tower")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) {
            entity += Position()
            entity += Tower()
        }
    }

    /** The override that puts [team] on a freshly spawned tower. One object per side, reused. */
    public fun on(team: Int): SpawnOverrides =
        if (team == Team.SOLDIER) SOLDIER else UNDEAD

    private val SOLDIER = SpawnOverrides { context, entity ->
        with(context) { entity[Tower].team = Team.SOLDIER }
    }

    private val UNDEAD = SpawnOverrides { context, entity ->
        with(context) { entity[Tower].team = Team.UNDEAD }
    }
}
