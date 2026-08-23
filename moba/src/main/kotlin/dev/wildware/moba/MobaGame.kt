package dev.wildware.moba

import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.GameUnitReplicator
import dev.wildware.moba.level.TestLevelScene
import dev.wildware.moba.ability.MobaAbilityModule
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.PresentationFactory
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.snapshot.snapshotTimeTravel
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
     */
    public fun definition(): UdeaGameDef {
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
            modules = listOf(
                InputModule(MobaControls.BINDINGS),
                module,
                combat,
                RenderModule(),
            ),
            timeTravel = snapshotTimeTravel(componentRegistry()),
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
     */
    public fun host(mode: RenderMode, presentation: PresentationFactory? = null): GameHost =
        GameHost(mode, definition(), presentation)

    /**
     * What the snapshot ring records: where a unit is, and what it is.
     *
     * Both replicators are generated - `udea-codegen` runs over this module's `@Replicated`
     * classes - but the *schema* beside each one is hand-assembled, because a `FieldKind` list is
     * a claim about column types that no generator emits yet. The two must agree field for field,
     * in the replicator's own order, which is alphabetical by name and not declaration order:
     *
     * | component | fields, in index order |
     * |---|---|
     * | `Position` | `hp`, `x`, `y` - three floats |
     * | `GameUnit` | `kind`, `targetRaw`, `team` - three ints |
     *
     * `ComponentSchema.of` refuses a list whose length disagrees with `fieldNames`, so a field
     * added to either component fails here rather than silently shifting a column - but a *kind*
     * typed wrong at the right length is not caught by anything except `SnapshotRoundTripTest`
     * style coverage, which is why the table above is written out rather than left implied.
     */
    public fun componentRegistry(): ComponentRegistry = ComponentRegistry(
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
        ),
    )
}
