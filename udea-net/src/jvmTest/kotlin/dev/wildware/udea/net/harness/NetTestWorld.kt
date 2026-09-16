package dev.wildware.udea.net.harness

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.RingConfig
import dev.wildware.udea.core.snapshot.SnapshotRing
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldSnapshot

/**
 * A headless server world: real Fleks, real `NetIdIndex`, real `SnapshotService`, real ring.
 *
 * Nothing here is a stand-in for the snapshot spine. The replication tests capture through the
 * same `SnapshotService` the engine uses and read baselines out of the same `SnapshotRing` that
 * backs time travel, because "the ring is the baseline store" (spec 3.1) is a claim about *this*
 * ring and would be untested against a purpose-built double.
 */
internal class NetTestWorld(
    seed: Long = 20_260_823L,
    val registry: ComponentRegistry = NetTestComponents.registry(),
    ringConfig: RingConfig = RingConfig(),
) {

    val netIds: NetIdIndex = NetIdIndex(capacity = 4096, entityCapacity = 4096)
    val world: World = configureWorld {}
    val ctx: GameContext = testGameContext(
        config = EngineConfig(seed = seed),
        configure = { rng = DefaultRngService(seed) },
    )
    val ring: SnapshotRing = SnapshotRing(registry, ringConfig)
    val snapshots: SnapshotService = SnapshotService(registry, world, ctx, netIds)

    /**
     * The real simulation, used only to advance the clock.
     *
     * `SimClock.advance` is `internal` to `udea-core` on purpose — only the kernel moves time —
     * so a test outside that module cannot fake a tick even if it wanted to. Driving the real
     * `WorldSimulation` is therefore not ceremony: it is the only way to advance a tick, and it
     * means these tests capture on exactly the cadence an assembled game does.
     */
    val sim: WorldSimulation = WorldSimulation(ctx, world)

    init {
        ctx.scenes.requestScene(SceneId("arena"))
    }

    /** Spawns an entity carrying [Mover], [Vitals] when [withVitals] and [Loadout] when [withLoadout]. */
    fun spawn(
        x: Float,
        y: Float,
        teamId: Int = 0,
        withVitals: Boolean = true,
        withLoadout: Boolean = false,
    ): NetId {
        val entity: Entity = world.entity {
            it += Mover(x, y, teamId, ctx.clock.tick)
            if (withVitals) it += Vitals()
            if (withLoadout) it += Loadout()
        }
        return netIds.allocate(entity)
    }

    /** Removes [netId] from the world and frees its id. */
    fun despawn(netId: NetId) {
        val entity = netIds.resolveOrNull(netId) ?: return
        netIds.free(netId)
        world -= entity
    }

    /** The live [Mover] of [netId]. */
    fun mover(netId: NetId): Mover {
        val entity = netIds.resolveOrNull(netId) ?: error("$netId is not live")
        return with(world) { entity[Mover] }
    }

    /** The live [Vitals] of [netId]. */
    fun vitals(netId: NetId): Vitals {
        val entity = netIds.resolveOrNull(netId) ?: error("$netId is not live")
        return with(world) { entity[Vitals] }
    }

    /** The live [Loadout] of [netId]. */
    fun loadout(netId: NetId): Loadout {
        val entity = netIds.resolveOrNull(netId) ?: error("$netId is not live")
        return with(world) { entity[Loadout] }
    }

    /** Advances the clock and captures the world into the ring. Returns the committed snapshot. */
    fun captureTick(): WorldSnapshot {
        sim.step()
        val slot = ring.acquire()
        snapshots.captureInto(slot)
        ring.commit(slot)
        return slot
    }
}
