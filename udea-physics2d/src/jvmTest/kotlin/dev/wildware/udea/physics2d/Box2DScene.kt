package dev.wildware.udea.physics2d

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaGame
import dev.wildware.udea.core.physics.BodyHandle
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.Capsule
import dev.wildware.udea.core.physics.Chain
import dev.wildware.udea.core.physics.Circle
import dev.wildware.udea.core.physics.ContactListener
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.physics.PhysicsSnapshotTypes
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.core.spatial.Transform3DReplicator
import dev.wildware.udea.generated.CoreUdeaRegistry

/**
 * A whole headless game with Box2D installed the way a game installs it: a real `UdeaGameDef`
 * with [Physics2DModule] in its module list, stepped by the real `WorldSimulation`, captured by the
 * real `SnapshotService` through the physics components' generated replicators.
 *
 * No window, no GL context and no Kool: this is the dedicated-server configuration.
 */
internal class Box2DScene(
    settings: Physics2DSettings = Physics2DSettings(),
    tickRate: Int = 60,
) : AutoCloseable {

    val module = Physics2DModule(settings)

    private val def = UdeaGameDef(
        registry = CoreUdeaRegistry,
        modules = listOf(module),
        config = EngineConfig(tickRate = tickRate, seed = 20_260_919L),
    )

    val game: UdeaGame = def.build()

    val world: World get() = game.world

    val netIds = def.core.netIds

    /** The installed solver, which is also `game.ctx.physics`. */
    val physics: Box2DPhysicsWorld = module.backend as Box2DPhysicsWorld

    /**
     * The physics components and `Transform3D`, so a capture and a hash see the pose a body drives
     * (issue #247). An entity without a `Transform3D` contributes nothing to its column.
     */
    val registry = ComponentRegistry(PhysicsSnapshotTypes.all() + transform3DType())

    val snapshots = SnapshotService(registry, game.world, game.ctx, netIds)

    private val scratch: WorldSnapshot = snapshots.newSnapshot()

    /** Contact begins and ends seen by a listener installed on every scene. */
    var contactBegins = 0
        private set
    var contactEnds = 0
        private set

    init {
        physics.addContactListener(object : ContactListener {
            override fun onBeginContact(a: BodyHandle, b: BodyHandle) {
                contactBegins++
            }

            override fun onEndContact(a: BodyHandle, b: BodyHandle) {
                contactEnds++
            }
        })
    }

    /** Spawns an entity carrying [body] and [shapes], with a `NetId`, as a game spawns one. */
    fun spawn(body: PhysicsBody, vararg shapes: Component<*>): NetId {
        val entity = world.entity {
            it += body
            it += shapes.toList()
        }
        return netIds.allocate(entity)
    }

    fun entityOf(id: NetId): Entity = checkNotNull(netIds.resolveOrNull(id)) { "$id is not live" }

    fun bodyOf(id: NetId): PhysicsBody = with(world) { entityOf(id)[PhysicsBody] }

    /** The pose a dynamic body drives, and a kinematic or static one is driven by (issue #247). */
    fun transformOf(id: NetId): Transform3D = with(world) { entityOf(id)[Transform3D] }

    fun step() {
        game.simulation.step()
    }

    /** `WorldHasher.hash` of a fresh capture: fields, tick, random streams and id allocator. */
    fun hash(): Long {
        snapshots.captureInto(scratch)
        return WorldHasher.hash(scratch)
    }

    /** Steps [ticks] times and returns the hash after each step. */
    fun run(ticks: Int): LongArray = LongArray(ticks) {
        step()
        hash()
    }

    override fun close() {
        module.close()
    }

    companion object {

        /** `Transform3D`'s snapshot registration: nine floats, in the replicator's sorted order. */
        private fun transform3DType() = fleksComponentType(
            Transform3DReplicator,
            ComponentSchema.of(Transform3DReplicator, "Transform3D", List(Transform3DReplicator.fieldNames.size) { FieldKind.Float }),
            Transform3D,
        ) { Transform3D() }

        /**
         * The standard scene: bodies falling, stacking and colliding.
         *
         * A static floor; a stack of boxes that has to settle; circles dropped with sideways
         * velocity into it; spinning capsules; and a static chain ramp. Every shape type, every
         * body kind except kinematic, and contacts from the first second on.
         */
        fun Box2DScene.buildStandardScene() {
            spawn(PhysicsBody(kind = BodyKind.Static, x = 0f, y = -0.5f), Box(halfWidth = 20f, halfHeight = 0.5f))
            spawn(
                PhysicsBody(kind = BodyKind.Static),
                Chain(floatArrayOf(-12f, 6f, -8f, 3f, -5f, 1.5f)),
            )
            for (level in 0 until 6) {
                spawn(PhysicsBody(x = 0.02f * level, y = 0.5f + level * 1.05f), Box(0.5f, 0.5f))
            }
            for (index in 0 until 8) {
                spawn(
                    PhysicsBody(x = -3.5f + index, y = 9f + index * 0.7f, linearX = if (index % 2 == 0) 1.5f else -1.5f),
                    Circle(0.4f),
                )
            }
            for (index in 0 until 3) {
                spawn(
                    PhysicsBody(x = -9f + index * 0.6f, y = 8f + index, angularVelocity = 2f + index),
                    Capsule(radius = 0.25f, halfHeight = 0.4f),
                )
            }
        }
    }
}
