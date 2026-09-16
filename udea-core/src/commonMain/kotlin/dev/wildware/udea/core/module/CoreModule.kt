package dev.wildware.udea.core.module

import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.movement.CharacterMoverSystem
import dev.wildware.udea.core.movement.SceneCollision
import dev.wildware.udea.core.physics.NoOpPhysicsWorld
import dev.wildware.udea.core.physics.PhysicsStepSystem
import dev.wildware.udea.core.physics.TeleportSystem
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.scene.BarrierSceneManager
import dev.wildware.udea.core.scene.ClearCueQueue
import dev.wildware.udea.core.serviceKey

/**
 * The kernel's own module: the services every simulation needs, and the systems it owns.
 *
 * Added automatically by [UdeaGameDef], first, so a game module's `context` hook runs after
 * these and can replace any of them by assignment — swapping [NoOpPhysicsWorld] for a real
 * Box2D world, or the default RNG for a recorded one — without the kernel knowing those exist.
 *
 * ## Why the services are created in the constructor
 *
 * The barrier, the id index, the cue queue and the scene manager reference each other: the
 * scene manager queues onto the barrier and clears the id index, and the cue-queue teardown
 * step holds the queue. Building them here rather than inside [context] means a host can reach
 * them (`def.core.scenes.register(myScene)`) *before* the world exists, which is when scenes
 * have to be registered.
 *
 * They are instance state on a module the caller constructs, not globals: two
 * [UdeaGameDef]s in one JVM get two of everything, which is the whole point of deleting
 * `lateinit var gameScreen`.
 */
public class CoreModule(
    /** How many live [NetId]s this simulation may hold. */
    netIdCapacity: Int = UdeaGameDef.DEFAULT_ENTITY_CAPACITY,
) : UdeaModule {

    override val name: String get() = "core"

    /** The one between-tick mutation queue (spec 5). */
    public val barrier: SimBarrier = SimBarrier()

    /** The one place a [NetId] becomes a Fleks entity. */
    public val netIds: NetIdIndex = NetIdIndex(capacity = netIdCapacity, entityCapacity = netIdCapacity)

    /** Where simulation cues go until presentation drains them. */
    public val cues: CueQueue = CueQueue()

    /**
     * Scene lifecycle. Register scenes on this before [UdeaGameDef.build], then
     * `ctx.scenes.requestScene(id)` to swap.
     *
     * Its teardown clears [cues]. The snapshot ring's teardown is not wired here because the
     * ring needs a `ComponentRegistry`, which is generated per game and is not the kernel's to
     * invent — a host that has one adds `ClearSnapshotRing` itself.
     */
    public val scenes: BarrierSceneManager =
        BarrierSceneManager(barrier, netIds, listOf(ClearCueQueue(cues)))

    /** Physics with no solver. A game module replaces it with a Box2D-backed world. */
    public val physics: NoOpPhysicsWorld = NoOpPhysicsWorld()

    /**
     * The static walls `CharacterMover` sweeps against (spec 3.4).
     *
     * Empty until a scene loads geometry into it, which a scene load does through the barrier.
     * Held here for the same reason the barrier and the id index are: a host has to be able to
     * reach it before the world exists, because that is when a scene's collision is built.
     */
    public val sceneCollision: SceneCollision = SceneCollision()

    override fun context(builder: GameContextBuilder) {
        builder.rng = DefaultRngService(builder.config.seed)
        builder.physics = physics
        builder.scenes = scenes
        builder.cues = cues
        builder.service(SimBarrier.KEY, barrier)
        builder.service(NET_IDS, netIds)
    }

    override fun simulation(registry: SimRegistry) {
        // Constructor references, not class objects: the compiler checks that the system takes
        // a PhysicsWorld and that this module has one to give it.
        registry.add(SimPhase.PreSimulation, { ctx -> TeleportSystem(ctx.physics) })
        // The authoritative movement model, ahead of the solver by phase ordinal (spec 3.4).
        // Not `ctx.something`: the geometry holder is this module's own state, so the system
        // names what it depends on in its constructor and the compiler checks it.
        registry.add(SimPhase.Movement, { _ -> CharacterMoverSystem(sceneCollision) })
        registry.add(SimPhase.Physics, { ctx -> PhysicsStepSystem(ctx.physics) }) {
            // Cross-phase, checked rather than dropped: move the mover after the solver and world
            // construction fails here naming both systems, instead of Box2D quietly getting to
            // decide where a character ended up - which is the whole of what spec 3.4 demotes.
            after<CharacterMoverSystem>()
            // Cross-phase and therefore already true by phase ordinal. Kept as a live
            // assertion: move either system's phase so that a teleport would land after the
            // step and world construction fails here, naming both systems, instead of the
            // teleport quietly taking effect a tick late.
            after<TeleportSystem>()
        }
    }

    public companion object {
        /**
         * The key the [NetIdIndex] is registered under.
         *
         * A [ServiceKey] rather than a field on `GameContext`: the context holds a small fixed
         * set of services and its documented extension point is exactly this. It is declared
         * here rather than beside `NetIdIndex` because `dev.wildware.udea.core.identity` must
         * not import `dev.wildware.udea.core` — that would make the two packages cyclic.
         */
        public val NET_IDS: ServiceKey<NetIdIndex> = serviceKey("NetIdIndex")
    }
}
