package dev.wildware.moba

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
        val module = MobaModule()
        val definition = UdeaGameDef(
            // `RenderModule` is in the list for **every** mode, including the headless server.
            // It contributes one simulation system, `InterpSnapshotSystem`, and leaving it out of
            // the headless build would make "all three modes run the identical Simulation" false
            // in the one place it is cheapest to keep true. The cost to a server is one lookup
            // per tick against a family that is empty until something spawns a physics body.
            modules = listOf(module, RenderModule()),
            timeTravel = snapshotTimeTravel(componentRegistry()),
        )
        module.spawner = BlueprintSpawner(
            barrier = definition.core.barrier,
            netIds = definition.core.netIds,
            placement = PositionPlacement,
        )
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
     * What the snapshot ring records.
     *
     * One entry, because [Position] is the whole of this game's state. It is hand-assembled for
     * the same reason [PositionReplicator] is hand-written: `udea-codegen` is not pointed at
     * `moba` yet, so the registry a `@Replicated` component would have produced has to be typed
     * out. Adding a second component today means editing three files that nothing cross-checks.
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
        ),
    )
}
