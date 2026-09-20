package dev.wildware.udea.physics2d

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.identity.NetIdVisitor
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.module.after
import dev.wildware.udea.core.module.before
import dev.wildware.udea.core.physics.BodyHandle
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.PhysicsBody
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
 * 3. `Physics`: [DrivenVelocitySystem] hands the solver any linear velocity a game wrote onto a
 *    dynamic `PhysicsBody` since the last step - how a character walks (issue #250).
 * 4. `Physics`: `PhysicsStepSystem` advances Box2D one tick and copies every body's pose,
 *    velocity and sleep state back into its `PhysicsBody`. That copy happens once per tick,
 *    inside the solver's own step, and is the only way a solver result reaches the components.
 *
 * ## 3D entities: `Transform3D` on the ground plane
 *
 * An entity that also carries a `Transform3D` (issue #247) is placed by it and, if dynamic, moves
 * it. Three more `Physics` systems wrap the ones above: [Transform3DSeedSystem] before step 2
 * builds a new body where `Transform3D.x/y/rotationZ` says; [BodyFollowsTransform3DSystem] between
 * steps 2 and 4 carries each kinematic body to its `Transform3D` in one step, pushing what it meets,
 * and teleports a static one whose `Transform3D` moved; and [Transform3DFromBodySystem] after step 4
 * copies each dynamic body's solved pose into its `Transform3D`. `z` and the other rotations and the
 * scale stay the game's. So a 3D game moves a dynamic body the way a 2D one does - velocity, or a
 * `Teleport` - and moves a kinematic or static one by writing its `Transform3D`. An entity with no
 * `Transform3D` is untouched by all three.
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
        registry.add(SimPhase.Physics, { Transform3DSeedSystem() }) {
            before<PhysicsReconcileSystem>()
        }
        registry.add(SimPhase.Physics, { ctx ->
            PhysicsReconcileSystem(openedBackend(), ctx[CoreModule.NET_IDS])
        }) {
            before<PhysicsStepSystem>()
        }
        registry.add(SimPhase.Physics, { ctx ->
            BodyFollowsTransform3DSystem(openedBackend(), ctx[CoreModule.NET_IDS])
        }) {
            after<PhysicsReconcileSystem>()
            before<PhysicsStepSystem>()
        }
        registry.add(SimPhase.Physics, { ctx ->
            DrivenVelocitySystem(openedBackend(), ctx[CoreModule.NET_IDS])
        }) {
            after<PhysicsReconcileSystem>()
            before<PhysicsStepSystem>()
        }
        registry.add(SimPhase.Physics, { Transform3DFromBodySystem() }) {
            after<PhysicsStepSystem>()
        }
    }

    private fun openedBackend(): SolverBackend = checkNotNull(backend) { "context runs before simulation" }

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

/**
 * Hands the solver every linear velocity a game wrote onto a dynamic `PhysicsBody` this tick.
 *
 * ## The gap it closes
 *
 * `PhysicsBody`'s velocity fields reached Box2D only when the body was built: afterwards the step's
 * write-back overwrote them with the solver's own, so a system that set a walk velocity every tick
 * was writing into a field nobody read. That made a character controller on the ground plane
 * impossible to write with what [Physics2DModule] offered - a kinematic body is not stopped by a
 * static one, and a `Teleport` ignores collision - which is the wall Hollow's player met (#250).
 *
 * ## Why it cannot change a scene that does not use it
 *
 * The push is skipped unless the component's velocity differs, **bit for bit**, from what the last
 * write-back put there. A scene in which nothing writes a velocity therefore makes no native call
 * at all, and `DrivenBodyTest` asserts that as a count rather than leaving it as a claim.
 *
 * Visits entities in ascending `NetId`, as its two siblings in this module do. The order decides
 * nothing here - setting one body's velocity reads nothing and touches no other body - and it is
 * kept anyway so that every per-tick walk of the bodies in this module is the same walk.
 */
internal class DrivenVelocitySystem(
    private val backend: SolverBackend,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val visitor = object : NetIdVisitor {
        override fun visit(netId: NetId, entity: Entity) {
            val body = with(world) { entity.getOrNull(PhysicsBody) } ?: return
            if (body.kind != BodyKind.Dynamic || !body.handle.isValid) return
            backend.pushWrittenVelocity(body)
        }
    }

    override fun onTick() {
        netIds.forEachLive(visitor)
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

    /**
     * Gives the kinematic body [handle] the linear and angular velocity that carries it to
     * ([x], [y], [angle]) in exactly one step, waking it if it must move.
     */
    fun moveKinematicTo(handle: BodyHandle, x: Float, y: Float, angle: Float)

    /**
     * Hands the solver the linear velocity on [component], if a game wrote one since the last step.
     *
     * "If a game wrote one" is decided by comparing the component's two velocity floats, bit for
     * bit, against what the solver itself last reported for that body - so a component nobody
     * touched costs two comparisons and no native call. See [DrivenVelocitySystem].
     */
    fun pushWrittenVelocity(component: PhysicsBody)
}

/** Opens the Box2D world for this target. */
internal expect fun openBox2D(settings: Physics2DSettings, tickRate: Int): SolverBackend
