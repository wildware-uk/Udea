package dev.wildware.udea.nav

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.UdeaGame
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.core.spatial.Transform3DReplicator
import dev.wildware.udea.generated.NavUdeaRegistry

/**
 * A whole headless game with navigation installed the way a game installs it: a real `UdeaGameDef`
 * with [NavModule] in its module list, stepped by the real `WorldSimulation`, captured by the real
 * `SnapshotService` through the nav components' generated replicators.
 *
 * No window, no GL context and no Kool - this is the dedicated-server configuration, which is also
 * what a replay runs on. The same fixture serves the crowd test and the determinism test, so what
 * the second one rewinds is the world the first one measured.
 */
internal class NavScene(
    val layout: NavGridLayout,
    seed: Long = 20_260_919L,
) {

    val module: NavModule = NavModule(layout)

    private val def = UdeaGameDef(
        registry = NavUdeaRegistry,
        modules = listOf(module),
        config = EngineConfig(tickRate = 60, seed = seed),
    )

    val game: UdeaGame = def.build()

    val world: World get() = game.world

    val netIds = def.core.netIds

    /** The navigation service the systems read, and the tool would. */
    val navigation: Navigation get() = game.ctx[NavModule.NAVIGATION]

    /** The nav components and `Transform3D`: everything a rewind has to put back. */
    val registry = ComponentRegistry(NavSnapshotTypes.all() + transform3DType())

    val snapshots = SnapshotService(registry, game.world, game.ctx, netIds)

    private val scratch: WorldSnapshot = snapshots.newSnapshot()

    /** Spawns a unit at ([x], [y]) with no order yet. */
    fun spawnUnit(x: Float, y: Float, radius: Float = 0.3f, speed: Float = 0.05f): NetId {
        val entity = world.entity {
            it += Transform3D(x = x, y = y)
            it += NavAgent(radius = radius, speed = speed)
        }
        return netIds.allocate(entity)
    }

    /** Spawns a building of the given footprint, which blocks the ground under it. */
    fun spawnBuilding(x: Float, y: Float, halfWidth: Float, halfDepth: Float): NetId {
        val entity = world.entity {
            it += Transform3D(x = x, y = y)
            it += NavObstacle(halfWidth = halfWidth, halfDepth = halfDepth)
        }
        return netIds.allocate(entity)
    }

    /** Removes an entity, as a destroyed building leaves the world. */
    fun destroy(id: NetId) {
        val entity = entityOf(id)
        netIds.free(id)
        world -= entity
    }

    /** Orders [id] to walk to ([x], [y]). */
    fun order(id: NetId, x: Float, y: Float) {
        agentOf(id).orderTo(x, y)
    }

    fun entityOf(id: NetId): Entity = checkNotNull(netIds.resolveOrNull(id)) { "$id is not live" }

    fun agentOf(id: NetId): NavAgent = with(world) { entityOf(id)[NavAgent] }

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

    companion object {

        /** `Transform3D`'s snapshot registration: nine floats, in the replicator's sorted order. */
        fun transform3DType() = fleksComponentType(
            Transform3DReplicator,
            ComponentSchema.of(
                Transform3DReplicator,
                "Transform3D",
                List(Transform3DReplicator.fieldNames.size) { FieldKind.Float },
            ),
            Transform3D,
        ) { Transform3D() }
    }
}
