package dev.wildware.udea.physics2d

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.module.before
import dev.wildware.udea.core.physics.PhysicsStepSystem
import dev.wildware.udea.core.physics.PhysicsWorld

/**
 * Real 2D physics for a Udea game: Box2D 3, installed as the game's `ctx.physics`.
 *
 * Add it to `UdeaGameDef.modules` and every entity carrying a `PhysicsBody`, one or more shape
 * components and a `NetId` becomes a Box2D body at the next tick. Nothing else in the game
 * changes: systems keep reading and writing plain floats on `PhysicsBody`, `Teleport` still moves
 * a body discontinuously, and `ctx.physics` answers raycasts and overlaps. No Box2D type appears
 * anywhere a game can see.
 *
 * ## What a tick does, in order
 *
 * 1. `PreSimulation`: `TeleportSystem` applies queued teleports (`udea-core`).
 * 2. `Physics`: [PhysicsReconcileSystem] makes the solver match the components - a body for every
 *    new entity, none for a destroyed one, a fresh body where a shape was edited, and a loud
 *    failure where static geometry changed.
 * 3. `Physics`: `PhysicsStepSystem` advances Box2D one tick and copies every body's pose,
 *    velocity and sleep state back into its `PhysicsBody`. That copy happens once per tick,
 *    inside the solver's own step, and is the only way a solver result reaches the components.
 *
 * Add `PhysicsSnapshotTypes.all()` to the game's `ComponentRegistry` as well, or a rewind cannot
 * see a body at all.
 *
 * ## Lifetime
 *
 * The Box2D world is native memory. It is opened when the game is built and freed by [close],
 * which a host calls when it is done with the game. A module builds one game: building a second
 * from the same instance would open a second native world behind the first one's back, so it
 * fails instead.
 */
public class Physics2DModule(
    /** The solver's fixed configuration. */
    public val settings: Physics2DSettings = Physics2DSettings(),
) : UdeaModule, AutoCloseable {

    override val name: String get() = "physics2d"

    /** The world [context] opened; `ctx.physics` is the same object. Null before and after. */
    internal var backend: SolverBackend? = null
        private set

    override fun context(builder: GameContextBuilder) {
        check(backend == null) {
            "this Physics2DModule already opened a Box2D world; build each game with its own module"
        }
        val opened = openBox2D(settings, tickRate = builder.config.tickRate)
        backend = opened
        builder.physics = opened
    }

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Physics, { ctx ->
            PhysicsReconcileSystem(checkNotNull(backend) { "context runs before simulation" }, ctx[CoreModule.NET_IDS])
        }) {
            before<PhysicsStepSystem>()
        }
    }

    /** Frees the native world. Safe to call twice, and before the game was ever built. */
    override fun close() {
        backend?.close()
        backend = null
    }
}

/**
 * Makes the solver agree with the components, once per tick, before it steps.
 *
 * A system rather than a hook on component add and remove, so that everything it does happens at
 * one known point in the tick and in one deterministic order: bodies are created in ascending
 * `NetId`, which two processes holding the same entities agree on whatever order they spawned
 * them in.
 */
internal class PhysicsReconcileSystem(
    private val backend: SolverBackend,
    private val netIds: NetIdIndex,
) : SimSystem() {

    override fun onTick() {
        backend.reconcile(world, netIds)
    }
}

/** What the module needs from a solver beyond `PhysicsWorld`: reconciliation and a native free. */
internal interface SolverBackend : PhysicsWorld, AutoCloseable {

    /**
     * Creates bodies for entities that gained a `PhysicsBody`, destroys bodies whose entity lost it
     * or is gone, rebuilds a body whose shape components or kind changed, and throws
     * [StaticGeometryChangedException] if a `Chain` changed.
     */
    fun reconcile(world: World, netIds: NetIdIndex)
}

/** Opens the Box2D world for this target. */
internal expect fun openBox2D(settings: Physics2DSettings, tickRate: Int): SolverBackend
