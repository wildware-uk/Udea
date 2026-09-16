package dev.wildware.udea.core.module

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.NetRole
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.level.LevelComponentModule
import dev.wildware.udea.core.level.LevelHooks
import dev.wildware.udea.core.level.LevelService
import dev.wildware.udea.core.loop.TimeTravelFactory
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.registry.UdeaRegistry

/**
 * What a game *is*, as a value: its modules, its knobs and its authority role.
 *
 * One definition drives every [dev.wildware.udea.core.host.RenderMode] — dedicated server, CI,
 * the agent's harness, fast-forward and the player all build from this, so there is no second
 * code path that could simulate differently (spec 3.5).
 *
 * `UdeaGameManager` was the opposite: a class that decided its own system list at runtime from
 * annotations, could only be constructed with a LibGDX `Application` behind it, and had no
 * value anywhere describing what the game contained.
 */
public class UdeaGameDef(
    /**
     * Every generated module registry this game's program contains - its launcher's
     * `<Module>UdeaRegistry` (issue #202).
     *
     * Required rather than defaulted. A default would be the one way left to build a game whose
     * level files silently lack a module's components, which is the failure the generated
     * registry exists to turn into a compile error. A world built from the kernel alone passes
     * `udea-core`'s own `CoreUdeaRegistry`, and says so where it is built.
     */
    public val registry: UdeaRegistry,
    /** Game and engine-extension modules, in declaration order. [CoreModule] is implicit. */
    public val modules: List<UdeaModule>,
    public val config: EngineConfig = EngineConfig(),
    public val role: NetRole = NetRole.Standalone,
    /** Fleks' initial entity capacity. Grows on demand; sizing it right avoids the regrow. */
    public val entityCapacity: Int = DEFAULT_ENTITY_CAPACITY,
    /**
     * Builds the snapshot ring the simulation records into, or `null` for a game with no
     * history.
     *
     * This field **is** the "does this game record?" decision. A definition without one
     * produces a `WorldSimulation` whose `travel` is `null`: no ring is allocated, capture
     * costs one null check per tick, and every `TimeControl` time-travel call answers
     * `no_snapshot_ring`. A dedicated server therefore does not pay for a 64MB ring nobody
     * reads, and it does not pay because the ring was never built rather than because someone
     * remembered to configure it away.
     *
     * A factory and not a [dev.wildware.udea.core.loop.TimeTravel], because a ring needs the
     * `World` and the `GameContext` that [build] is what creates. `snapshotTimeTravel(...)` in
     * `dev.wildware.udea.core.snapshot` is the one the engine ships; it takes the generated
     * `ComponentRegistry`, which is why the kernel cannot supply this itself.
     */
    public val timeTravel: TimeTravelFactory? = null,
) {

    init {
        require(entityCapacity > 0) { "entityCapacity must be positive, was $entityCapacity" }
        require(modules.none { it is CoreModule }) {
            "CoreModule is added automatically and must not appear in `modules`; listing it " +
                "would register its systems twice"
        }
    }

    /**
     * The kernel's own module, holding the services it creates.
     *
     * Exposed so a host can reach the scene manager to register scenes, or the id index to
     * build a `SnapshotService`, without those having to be dug back out of the built context.
     */
    public val core: CoreModule = CoreModule(entityCapacity)

    /** [core] first, then [modules]. The order services are contributed and systems declared. */
    public val allModules: List<UdeaModule> = buildList {
        add(core)
        addAll(modules)
    }

    /**
     * Builds the context, resolves the system order and configures the world.
     *
     * @throws SystemOrderException if the declared order cannot be realised.
     * @throws dev.wildware.udea.core.MissingServiceException if no module supplied a required
     *   service.
     */
    public fun build(): UdeaGame {
        val registry = SimRegistry()
        for (module in allModules) module.simulation(registry)
        val resolved = registry.resolve()

        val manifest = SystemManifest(
            resolved.map { registration ->
                SystemManifestEntry(
                    phase = registration.phase,
                    name = registration.name,
                    before = registration.runsBefore.map { it.java.name },
                    after = registration.runsAfter.map { it.java.name },
                )
            },
        )

        val ctx = gameContext {
            config = this@UdeaGameDef.config
            role = this@UdeaGameDef.role
            for (module in allModules) module.context(this)
            service(SystemManifest.KEY, manifest)
        }

        // Systems are constructed here and only here: `factory(ctx)` is an ordinary call the
        // compiler checked, inside the world configuration Fleks requires a system to be built
        // in. No `createInstance()`, no no-arg constructor requirement, no reflection.
        val world = configureWorld(entityCapacity) {
            injectables { gameContext(ctx) }
            systems {
                for (registration in resolved) add(registration.factory(ctx))
            }
        }

        // Built after the world and before the first tick, so the simulation holds its ring
        // for its whole life and there is no window in which a game is half wired for history.
        val travel = timeTravel?.create(ctx, world)

        val levelHooks = LevelHooks()
        for (module in allModules) module.level(levelHooks)
        val levels = LevelService(world, ctx, core.netIds, levelHooks) {
            LevelComponentModule.of(this@UdeaGameDef.registry)
        }

        return UdeaGame(ctx, world, WorldSimulation(ctx, world, travel = travel), manifest, levels)
    }

    override fun toString(): String =
        "UdeaGameDef(${allModules.size} modules, role=$role, seed=${config.seed})"

    public companion object {
        /** A lane's worth of champions, creeps and projectiles without a regrow. */
        public const val DEFAULT_ENTITY_CAPACITY: Int = 2048
    }
}

/**
 * A built simulation: the context, the world, the thing that steps it, the resolved order, and
 * the level files that save and load its world.
 *
 * Deliberately not a god object — it holds references and no behaviour of its own. It exists
 * so `build()` can hand back everything a host needs without a caller having to reconstruct
 * which world went with which context, which is exactly the mistake two worlds in
 * one JVM makes easy.
 */
public class UdeaGame internal constructor(
    public val ctx: GameContext,
    public val world: World,
    public val simulation: WorldSimulation,
    /** Also reachable as `world.systemManifest()`; here so a host need not go through Fleks. */
    public val manifest: SystemManifest,
    /**
     * Saves this world to a level file and loads one into it (issue #191). A property of the
     * built game rather than a `GameContext` service, because it needs the world, which is built
     * after the context, and because no system has any business saving a level mid-tick.
     */
    public val levels: LevelService,
) {
    override fun toString(): String = "UdeaGame(${manifest.size} systems, ${world.numEntities} entities)"
}
