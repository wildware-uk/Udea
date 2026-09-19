package dev.wildware.udea.nav

import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.module.after
import dev.wildware.udea.core.serviceKey
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.ReplicatedComponentType
import dev.wildware.udea.core.snapshot.fleksComponentType

/**
 * Ground-plane navigation for a game: a grid of the map, routes over it, and units that walk them.
 *
 * Add it to `UdeaGameDef.modules` with the shape of the map, and from the next tick on every
 * entity carrying a [NavAgent] and a `Transform3D` walks to where its agent says, around every
 * entity carrying a [NavObstacle].
 *
 * ```kotlin
 * UdeaGameDef(
 *     registry = MyUdeaRegistry,
 *     modules = listOf(NavModule(NavGridLayout(-64f, -64f, cellSize = 0.5f, width = 256, height = 256))),
 * )
 * ```
 *
 * Add [NavSnapshotTypes.all] to the game's `ComponentRegistry` as well, or a rewind cannot see an
 * order at all: a component outside the registry is invisible to capture rather than partly
 * captured, and a rewound unit would walk to a goal the restored world never gave it.
 *
 * ## What a tick does, in order
 *
 * 1. `PreSimulation`: [NavGridSystem] makes the grid agree with the buildings that are standing,
 *    rebuilding it when they have changed.
 * 2. `Movement`: [NavMoveSystem] routes, steps, separates and settles every unit.
 *
 * `Movement` is the phase `CharacterMover` runs in, and for the same reason: this is authoritative
 * movement, decided before the solver in `Physics` reacts to it.
 *
 * ## What it does not do
 *
 * It does not draw anything - a module that also drew would name `udea-render`'s types and invert
 * the module arrows - and it does not collide units with anything but each other and the grid. A
 * game that wants units to shove crates puts a `PhysicsBody` on them as well; this module writes
 * `Transform3D`, which `udea-physics2d` reads as the ground-plane pose (issue #247).
 */
public class NavModule(
    /** Where the nav grid sits on the ground plane, and how finely it is cut. */
    public val layout: NavGridLayout,
) : UdeaModule {

    override val name: String get() = "nav"

    override fun context(builder: GameContextBuilder) {
        // Open ground to begin with. `NavGridSystem` stamps the world's buildings onto it on the
        // first tick, so a game that loads a level with a hundred buildings in it does not have to
        // hand them to this constructor as well - the entities are the truth about the map.
        builder.service(NAVIGATION, Navigation(NavGridBuilder(layout).build()))
    }

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.PreSimulation, { ctx ->
            NavGridSystem(ctx[NAVIGATION], ctx[CoreModule.NET_IDS])
        })
        registry.add(SimPhase.Movement, { ctx ->
            NavMoveSystem(ctx[NAVIGATION], ctx[CoreModule.NET_IDS])
        }) {
            // After the grid system, which is in an earlier phase: stated rather than assumed, so
            // that moving either one into the other's phase fails the build instead of silently
            // routing units over last tick's buildings.
            after<NavGridSystem>()
        }
    }

    public companion object {

        /**
         * Where a system or a tool reaches the navigation service.
         *
         * A `ServiceKey` rather than a field on `GameContext`: the kernel does not name navigation,
         * and the standards ask for a justification before a context field. There is none to give -
         * only this module's systems and the `nav.*` tools read it.
         */
        public val NAVIGATION: ServiceKey<Navigation> = serviceKey("nav.navigation")
    }
}

/**
 * The snapshot registrations for the navigation components, for a game's `ComponentRegistry`.
 *
 * A game that installs [NavModule] appends [all] to the list it builds its registry from, the way
 * a game installing physics appends `PhysicsSnapshotTypes.all()`. Capture walks the registry, so a
 * nav component left out of it is not partly captured, it is **invisible**: a rewind would leave
 * every unit holding the order the future gave it.
 *
 * ## The field kinds, written out
 *
 * `ComponentSchema.of` refuses a list whose length disagrees with `fieldNames`, but a kind typed
 * wrong at the right length is caught by nothing except a round trip. Each list below is in the
 * generated replicator's order, which is the lowered field names sorted ascending:
 *
 * | Component | Fields |
 * |---|---|
 * | `NavAgent` | `goalX`, `goalY`, `radius`, `speed`, `state`, `velocityX`, `velocityY` |
 * | `NavObstacle` | `halfDepth`, `halfWidth` |
 */
public object NavSnapshotTypes {

    /** One registration per `@Replicated` navigation component. Built fresh per call. */
    public fun all(): List<ReplicatedComponentType<*>> = listOf(
        fleksComponentType(
            NavAgentReplicator,
            ComponentSchema.of(
                NavAgentReplicator,
                "NavAgent",
                listOf(
                    FieldKind.Float,
                    FieldKind.Float,
                    FieldKind.Float,
                    FieldKind.Float,
                    FieldKind.Int,
                    FieldKind.Float,
                    FieldKind.Float,
                ),
            ),
            NavAgent,
        ) { NavAgent() },
        fleksComponentType(
            NavObstacleReplicator,
            ComponentSchema.of(
                NavObstacleReplicator,
                "NavObstacle",
                listOf(FieldKind.Float, FieldKind.Float),
            ),
            NavObstacle,
        ) { NavObstacle() },
    )
}
