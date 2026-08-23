package dev.wildware.udea.core.scene

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.physics.BodyDef
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.PhysicsBody

/** A scalar per entity, so "identical component state" is something a test can compare. */
internal class Marker(var value: Float = 0f, var band: Int = 0) : Component<Marker> {
    override fun type(): ComponentType<Marker> = Marker

    override fun toString(): String = "Marker($value, $band)"

    companion object : ComponentType<Marker>()
}

/**
 * A scene of [entityCount] entities whose state depends only on [seed] and the entity's index.
 *
 * Deterministic by construction rather than by discipline: there is no map iteration, no file
 * listing and no clock in here, so two runs of the same scene produce the same ids carrying the
 * same values — which is the property `SceneSwapTest` asserts field by field.
 */
internal class MarkerScene(
    override val id: SceneId,
    override val seed: Long,
    private val entityCount: Int,
    /** Gives every entity a physics body too, so a teardown has bodies to destroy. */
    private val withBodies: Boolean = false,
) : Scene {

    /** The ids minted by the most recent populate, in creation order. */
    val spawned = ArrayList<NetId>()

    /** What [SceneScope.spawnCount] read at the end of the most recent populate. */
    var scopeSpawnCount: Int = -1
        private set

    override fun populate(scope: SceneScope) {
        spawned.clear()
        var state = seed
        repeat(entityCount) { index ->
            state = state * GOLDEN + index
            val value = ((state ushr 20) and 0xFFFF).toFloat() / 1024f
            spawned += scope.spawn {
                it += Marker(value = value, band = index % 4)
                if (withBodies) {
                    it += PhysicsBody(x = value, y = -value)
                    it += Box()
                }
            }
        }
        if (withBodies) {
            // A scene builds its own bodies; a restore rebuilds them from the same components.
            scope.ctx.physics.rebuildFrom(scope.world, scope.netIds)
        }
        scopeSpawnCount = scope.spawnCount
    }

    private companion object {
        const val GOLDEN: Long = 6364136223846793005L
    }
}

/** Records the world's entity count and active scene at the end of every tick. */
internal class SceneProbeSystem(private val context: GameContext) : SimSystem() {

    val samples = ArrayList<Sample>()

    override fun onTick() {
        samples += Sample(context.clock.tick, world.numEntities, context.scenes.activeSceneId)
    }

    data class Sample(val tick: Tick, val entityCount: Int, val scene: SceneId?)
}

/** Asks for [target] on the tick [atTick], from inside a running tick. */
internal class SwapRequestSystem(
    private val context: GameContext,
    private val atTick: Long,
    private val target: SceneId,
) : SimSystem() {

    override fun onTick() {
        if (context.clock.tick.value == atTick) context.scenes.requestScene(target)
    }
}

/** Registers the probe last and the requester first, so both bracket a whole tick. */
internal class SceneProbeModule(
    private val swapAtTick: Long,
    private val target: SceneId?,
) : UdeaModule {

    override fun simulation(registry: SimRegistry) {
        if (target != null) {
            registry.add(SimPhase.Intent, { ctx -> SwapRequestSystem(ctx, swapAtTick, target) })
        }
        registry.add(SimPhase.Cleanup, { ctx -> SceneProbeSystem(ctx) })
    }
}

/** Creates a body directly, for a test that needs stale physics state before a swap. */
internal fun GameContext.createStaleBody(): Unit {
    physics.createBody(BodyDef(PhysicsBody(), NetId.NONE, emptyList()))
}
