package dev.wildware.moba

import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.GameUnitReplicator
import dev.wildware.moba.level.TestLevelScene
import dev.wildware.moba.ability.CharacterAttributes
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.ability.CombatantReplicator
import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.ability.CorpseReplicator
import dev.wildware.moba.ability.MobaAbilityModule
import dev.wildware.moba.ability.Motion
import dev.wildware.moba.ability.MotionReplicator
import dev.wildware.moba.ability.Projectile
import dev.wildware.moba.ability.ProjectileReplicator
import dev.wildware.moba.ability.UnitBlueprint
import dev.wildware.moba.match.MatchModule
import dev.wildware.moba.match.MatchState
import dev.wildware.moba.match.MatchStateReplicator
import dev.wildware.moba.match.Respawn
import dev.wildware.moba.match.RespawnReplicator
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.PresentationFactory
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.snapshot.snapshotTimeTravel
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilitiesReplicator
import dev.wildware.udea.gas.AttributeTable
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.AttributesReplicator
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GameplayEffectsReplicator
import dev.wildware.udea.render.RenderModule
import dev.wildware.udea.render.input.InputModule

/**
 * The **one** simulation, and the one place it is assembled.
 *
 * ## Why this exists as a separate object
 *
 * Spec 3.5 says the three [RenderMode]s "all run the identical `Simulation`". A design says that;
 * this object is what makes it true. `dev.wildware.moba.entry.MobaServer`, `.MobaClient` and the
 * debug-only `dev.wildware.moba.agent.MobaAgent` differ in exactly one expression each - the
 * [RenderMode] and the [PresentationFactory] they pass to [host] - and share every line that
 * decides what the game *is*. A behaviour that reproduces in one mode and not another is
 * therefore a bug in a renderer, not a second engine.
 *
 * A definition is built fresh per call rather than held as a value, because building it
 * constructs a `World`, a `SimBarrier` and a snapshot ring: two hosts over one definition would
 * share all three and each would tick the other's world.
 */
public object MobaGame {

    /** How the game names itself to `list_instances` and to `/tools`. */
    public const val NAME: String = "moba"

    /** Reported by `/tools`. Not a release version; `moba` has never been released. */
    public const val VERSION: String = "0.1.0"

    /**
     * A fresh definition, with a snapshot ring.
     *
     * The ring is what `time.snapshot`, `time.rewind` and `time.list_snapshots` need; a
     * definition without one produces a `TimeControl` that answers `no_snapshot_ring` to every
     * one of them. A dedicated server that never rewinds would legitimately pass `null` here, and
     * `moba` does not yet distinguish the two - see [componentRegistry] for the honest cost.
     *
     * @param extraModules modules appended **after** every module this game is made of, for a
     *   host that has to decorate a service the game already published. There is exactly one
     *   caller: `dev.wildware.moba.agent.MobaAgent` wraps `GameContextBuilder.cues` in a
     *   `CueEventMirror` so `events.recent_events` sees the fight. It is a parameter rather than
     *   a hook on `GameHost` because a module's `context` hook is the only place a decorator can
     *   see the value it is decorating, and appending is what puts this one last. The default is
     *   empty, so `MobaServer`, `MobaClient` and every test build the identical simulation.
     */
    public fun definition(extraModules: List<UdeaModule> = emptyList()): UdeaGameDef {
        val combat = MobaAbilityModule()
        val module = MobaModule(combat)
        val definition = UdeaGameDef(
            // `RenderModule` is in the list for **every** mode, including the headless server.
            // It contributes one simulation system, `InterpSnapshotSystem`, and leaving it out of
            // the headless build would make "all three modes run the identical Simulation" false
            // in the one place it is cheapest to keep true. The cost to a server is one lookup
            // per tick against a family that is empty until something spawns a physics body.
            // `InputModule` before `MobaModule`, so `IntentState` is on the context by the time
            // `PlayerControlSystem`'s factory asks for it - a module's `context` hook runs for
            // every module before any `simulation` hook does, but the *service lookup* happens
            // when the system is constructed, and constructing it needs the key to be there.
            //
            // It is in the list for **every** mode, including the headless server, for the same
            // reason `RenderModule` is: the three modes must run the identical simulation, and a
            // server whose tick has no `SimPhase.Intent` system in it is a different simulation
            // from the client's. A server's source is `IntentSource.NONE`, which costs one
            // virtual call per tick and produces an intent that is idle by construction.
            //
            // `MobaAbilityModule` is the game's ability system: the attributes, effects,
            // abilities and execs ported from the old example game onto `udea-gas`, plus the
            // `GasModule` that runs them. It is in the list for every mode for the same reason
            // the other two are. Every system it registers is scoped to a family that needs
            // `Combatant`, so it costs an empty family lookup per tick until something spawns a
            // unit that has one.
            //
            // `MatchModule` is the game *loop* - the match, the win rule, the restart and the
            // respawn - and it is in the list for every mode for a reason worth stating: a
            // definition assembled without it is the fight simulator this game was before, where
            // twenty-seven units killed each other and the process then sat static for ever. It
            // is handed `combat.attributes` and not a fresh `CharacterAttributes.create()`,
            // because an `AttributeId` is an index into one table and a respawn built over a
            // second table would restore the player's *armour* and leave its health at zero.
            modules = listOf(
                InputModule(MobaControls.BINDINGS),
                module,
                combat,
                MatchModule(combat.attributes),
                RenderModule(),
            ) + extraModules,
            // The registry is built over **this** module's attribute table and not a fresh
            // one: an `AttributeVector` is a dense positional array, so a registry indexed by
            // a different table would restore a unit's strength into its armour in silence.
            timeTravel = snapshotTimeTravel(componentRegistry(combat.attributes.table)),
        )
        module.spawner = BlueprintSpawner(
            barrier = definition.core.barrier,
            netIds = definition.core.netIds,
            placement = PositionPlacement,
        )
        // Registered, not loaded. `register` only makes the id addressable; the swap itself is a
        // barrier action, and submitting one here would queue work against a world that does not
        // exist until `definition.build()`. `MobaEntry.seed` is what asks for it, once, in every
        // entry point - so a scene an agent later swaps away from and back to is the same object
        // this line named.
        definition.core.scenes.register(TestLevelScene())
        return definition
    }

    /**
     * Builds a host for [mode] over a fresh [definition].
     *
     * @param presentation the LWJGL3 backend for [RenderMode.Offscreen] and
     *   [RenderMode.Windowed]. Ignored - never even invoked - in [RenderMode.Headless], which is
     *   `GameHost`'s own rule rather than a branch here.
     * @param extraModules see [definition].
     */
    public fun host(
        mode: RenderMode,
        presentation: PresentationFactory? = null,
        extraModules: List<UdeaModule> = emptyList(),
    ): GameHost = GameHost(mode, definition(extraModules), presentation)

    /**
     * **Everything a live entity carries**, so a rewind restores a world and not a silhouette.
     *
     * ## What this list being short used to cost
     *
     * It held two entries - `Position` and `GameUnit` - and a dressed unit carries nine. Capture
     * walks *this list* and asks each entry whether an entity has it, so the other seven were not
     * partly captured, they were invisible. A play agent measured the result on the real level:
     * rewinding 300 ticks brought back 27 units where 22 had been alive, five of them bare
     * `Position`+`GameUnit` shells with no art and no combat state, while total health and the
     * count of in-flight ability activations did not move at all, because the components holding
     * them had never been in a snapshot. `SnapshotCoverage` exists so that this can never again be
     * discovered by playing the game; `SnapshotRestoreProofTest` runs it over the real roster.
     *
     * ## The schema beside each replicator
     *
     * A `FieldKind` list is a claim about column types that no generator emits yet, so it is
     * hand-assembled here and must agree with the replicator field for field, **in the
     * replicator's own order, which is alphabetical by field name and not declaration order**:
     *
     * | component | fields, in index order |
     * |---|---|
     * | `CharacterView` | `character`, `flipX`, `startTick`, `state` - int, bool, long, enum-as-int |
     * | `Combatant` | `teamId` - one int |
     * | `Corpse` | `diedTick` - one long |
     * | `GameUnit` | `kind`, `targetRaw`, `team` - three ints |
     * | `Motion` | `damping`, `vx`, `vy` - three floats |
     * | `Player` | `facing`, `moveX`, `moveY` - three floats |
     * | `Position` | `hp`, `x`, `y` - three floats |
     * | `Projectile` | `damage`, `hitRadius`, `knockback`, `lifeTicks`, `owner`, `stunTicks`, `teamId` |
     * | `Attributes` | `base` - the whole vector, one object field |
     * | `Abilities` | `instances` - every activation record, one object field |
     * | `GameplayEffects` | `applied` - the whole effect list, one object field |
     * | `MatchState` | `endedTick`, `matchNumber`, `orcAlive`, `phase`, `seed`, `soldierAlive`, `startedTick`, `undeadAlive`, `winner` |
     * | `Respawn` | `deaths`, `maxHealth`, `readyTick`, `spawnX`, `spawnY` |
     *
     * `ComponentSchema.of` refuses a list whose length disagrees with `fieldNames`, so a field
     * added to a component fails here rather than silently shifting a column - but a *kind* typed
     * wrong at the right length is caught by nothing except a round trip, which is why the table
     * is written out rather than left implied.
     *
     * ## The three `udea-gas` components, and why their codecs are hand-written
     *
     * `Attributes`, `Abilities` and `GameplayEffects` are each a variable-length array of records.
     * `udea-codegen` lowers one property at a time and refuses anything that is not a scalar, so
     * none of the three can be `@Replicated` and none is in `net-components.lock`. Their codecs
     * carry the id their caller passes and default to 64, 65 and 66 - above that file's space, so
     * they cannot collide with a name in it, and `ComponentRegistry` refuses a duplicate id loudly
     * if it ever grows that far.
     *
     * @param attributes the table every unit's `Attributes` is indexed by. It **must** be the same
     *   table `MobaAbilityModule` dressed the units with: an `AttributeVector` is a dense array
     *   whose meaning is entirely positional, so a registry built over a different table would
     *   restore a unit's strength into its armour without anything noticing.
     */
    public fun componentRegistry(
        attributes: AttributeTable = CharacterAttributes.create().table,
    ): ComponentRegistry = ComponentRegistry(
        listOf(
            fleksComponentType(
                PositionReplicator,
                ComponentSchema.of(
                    PositionReplicator,
                    "Position",
                    listOf(FieldKind.Float, FieldKind.Float, FieldKind.Float),
                ),
                Position,
            ) { Position() },
            fleksComponentType(
                GameUnitReplicator,
                ComponentSchema.of(
                    GameUnitReplicator,
                    "GameUnit",
                    listOf(FieldKind.Int, FieldKind.Int, FieldKind.Int),
                ),
                GameUnit,
            ) { GameUnit() },
            fleksComponentType(
                CharacterViewReplicator,
                ComponentSchema.of(
                    CharacterViewReplicator,
                    "CharacterView",
                    listOf(FieldKind.Int, FieldKind.Bool, FieldKind.Long, FieldKind.Int),
                ),
                CharacterView,
            ) { CharacterView() },
            fleksComponentType(
                PlayerReplicator,
                ComponentSchema.of(
                    PlayerReplicator,
                    "Player",
                    listOf(FieldKind.Float, FieldKind.Float, FieldKind.Float),
                ),
                Player,
            ) { Player() },
            fleksComponentType(
                CombatantReplicator,
                ComponentSchema.of(CombatantReplicator, "Combatant", listOf(FieldKind.Int)),
                Combatant,
            ) { Combatant() },
            fleksComponentType(
                CorpseReplicator,
                ComponentSchema.of(CorpseReplicator, "Corpse", listOf(FieldKind.Long)),
                Corpse,
            ) { Corpse() },
            fleksComponentType(
                MotionReplicator,
                ComponentSchema.of(
                    MotionReplicator,
                    "Motion",
                    listOf(FieldKind.Float, FieldKind.Float, FieldKind.Float),
                ),
                Motion,
            ) { Motion() },
            fleksComponentType(
                ProjectileReplicator,
                ComponentSchema.of(
                    ProjectileReplicator,
                    "Projectile",
                    listOf(
                        FieldKind.Float,
                        FieldKind.Float,
                        FieldKind.Float,
                        FieldKind.Int,
                        FieldKind.NetId,
                        FieldKind.Int,
                        FieldKind.Int,
                    ),
                ),
                Projectile,
            ) { Projectile() },
            // The match itself. Without this line the scoreboard is not partly captured, it is
            // **invisible** to capture - so a `time.rewind` would restore twenty-seven units to
            // the middle of a fight and leave the singleton saying the match had already been
            // won, or destroy it outright. Nine fields, in the replicator's alphabetical order:
            // endedTick, matchNumber, orcAlive, phase, seed, soldierAlive, startedTick,
            // undeadAlive, winner. `phase` is an enum, carried as its ordinal, hence `Int`.
            // The match itself. Without this line the scoreboard is not partly captured, it is
            // **invisible** to capture - so a `time.rewind` would restore twenty-seven units to
            // the middle of a fight and leave the singleton saying the match had already been
            // won, or destroy it outright. Nine fields, in the replicator's alphabetical order:
            // endedTick, matchNumber, orcAlive, phase, seed, soldierAlive, startedTick,
            // undeadAlive, winner. `phase` is an enum, carried as its ordinal, hence `Int`.
            fleksComponentType(
                MatchStateReplicator,
                ComponentSchema.of(
                    MatchStateReplicator,
                    "MatchState",
                    listOf(
                        FieldKind.Long,
                        FieldKind.Int,
                        FieldKind.Int,
                        FieldKind.Int,
                        FieldKind.Long,
                        FieldKind.Int,
                        FieldKind.Long,
                        FieldKind.Int,
                        FieldKind.Int,
                    ),
                ),
                MatchState,
            ) { MatchState() },
            // The respawn timer, for the same reason: rewinding across a death has to put the
            // pending stand-up tick back with it, or a player rewound to just before they died
            // would stand up on a schedule from a future that no longer exists.
            // Fields: deaths, maxHealth, readyTick, spawnX, spawnY.
            fleksComponentType(
                RespawnReplicator,
                ComponentSchema.of(
                    RespawnReplicator,
                    "Respawn",
                    listOf(
                        FieldKind.Int,
                        FieldKind.Float,
                        FieldKind.Long,
                        FieldKind.Float,
                        FieldKind.Float,
                    ),
                ),
                Respawn,
            ) { Respawn() },
            attributesType(attributes),
            abilitiesType(),
            effectsType(),
        ),
    )

    /**
     * `Attributes`, through the hand-written dense-vector codec in `udea-gas`.
     *
     * The `create` lambda only ever runs when a restore rebuilds an entity the live world had
     * dropped - a unit that died after the keyframe - and it has to hand back a component indexed
     * by the same table the vector was captured against, which is why the table is threaded all
     * the way down here rather than defaulted at the point of use.
     */
    private fun attributesType(table: AttributeTable) = fleksComponentType(
        AttributesReplicator(table),
        ComponentSchema.of(AttributesReplicator(table), "Attributes", listOf(FieldKind.Object)),
        Attributes,
    ) { Attributes(table) }

    /**
     * `Abilities`, with the slot count a unit is dressed with.
     *
     * `AbilitiesReplicator` refuses to apply a vector whose slot count differs from the
     * component's, because the vector is positional; a resurrected unit must therefore be rebuilt
     * with the same slot count `UnitBlueprint.dress` gave it. That constant is the one place the
     * number is written down, so it is read from there rather than repeated.
     */
    private fun abilitiesType() = fleksComponentType(
        AbilitiesReplicator(),
        ComponentSchema.of(AbilitiesReplicator(), "Abilities", listOf(FieldKind.Object)),
        Abilities,
    ) { Abilities(UnitBlueprint.ABILITY_SLOTS) }

    /** `GameplayEffects`, whose whole applied list is one object field. */
    private fun effectsType() = fleksComponentType(
        GameplayEffectsReplicator(),
        ComponentSchema.of(GameplayEffectsReplicator(), "GameplayEffects", listOf(FieldKind.Object)),
        GameplayEffects,
    ) { GameplayEffects() }
}
