package dev.wildware.udea.core.snapshot

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.physics.PhysicsWorld
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.QueueingSceneManager
import dev.wildware.udea.core.fixtures.RecordingCueSink
import dev.wildware.udea.core.fixtures.RecordingPhysicsWorld
import dev.wildware.udea.core.fixtures.Vec2
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.GameLoop
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.loop.TimeControl
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.loop.simBarrier
import dev.wildware.udea.core.rng.DefaultRngService

/**
 * A whole headless simulation the snapshot tests can drive: world, context, ids, ring, loop.
 *
 * Everything here is real — a real Fleks world, the real `WorldSimulation`, the real
 * `SimBarrier`, the real `DefaultRngService`. The only fakes are the scene manager and the
 * physics world, which come from this module's published fixtures and are recording doubles.
 *
 * No GL, no window, no `LibGDX` application and no global. That is the point of the kernel,
 * and it is what lets a 1000-entity budget test run inside a plain JUnit method.
 */
internal class SnapshotWorld(
    seed: Long = 20_260_822L,
    scene: SceneId? = SceneId("arena"),
    idCapacity: Int = 4_096,
    ringConfig: RingConfig = RingConfig(),
    /**
     * Adds a [StampProbe] to the system list.
     *
     * It has to be requested at construction because a Fleks system may only be built inside a
     * world configuration scope — which is also what stops a test bolting one on mid-tick.
     */
    withProbe: Boolean = false,
    /**
     * The context's physics world.
     *
     * Injectable so a test can watch *when* `rebuildFrom` is called relative to the restore's
     * `Replicator.apply` calls, which is an ordering the default recording double cannot see.
     */
    physicsWorld: PhysicsWorld = RecordingPhysicsWorld(),
    /**
     * The component registry this world captures through.
     *
     * Injectable so two worlds can share one, which `DivergenceReport.compare` requires:
     * `WorldFieldStore.diffInto` refuses stores built from different registries, and it is
     * right to — a registry is a static description of component *types*, so two worlds
     * sharing one share nothing simulated and no ordering that a determinism gate is looking
     * for.
     */
    val registry: ComponentRegistry = TestComponents.registry(),
) {

    val netIds: NetIdIndex = NetIdIndex(capacity = idCapacity, entityCapacity = idCapacity)

    val barrier: SimBarrier = SimBarrier()

    val ctx: GameContext = gameContext {
        config = EngineConfig(seed = seed)
        // The production generator, not the fixture: SnapshotService requires a CapturableRng,
        // which is the whole reason the interface exists.
        rng = DefaultRngService(seed)
        physics = physicsWorld
        scenes = QueueingSceneManager(scene)
        cues = RecordingCueSink()
        simBarrier(barrier)
    }

    /** The probe, when one was asked for. Runs after the gameplay systems. */
    var probe: StampProbe? = null
        private set

    val world: World = configureWorld {
        injectables { gameContext(ctx) }
        systems {
            add(MovementSystem())
            add(VitalsSystem())
            if (withProbe) add(StampProbe().also { probe = it })
        }
    }

    val service: SnapshotService = SnapshotService(registry, world, ctx, netIds)

    val ring: SnapshotRing = SnapshotRing(registry, ringConfig, ctx.log)

    val simulation: WorldSimulation = WorldSimulation(ctx, world, barrier)

    val loop: GameLoop = GameLoop(simulation)

    val travel: SnapshotTimeTravel = SnapshotTimeTravel(service, ring, world, ctx, barrier)

    val time: TimeControl = TimeControl(loop, travel)

    val tick: Tick get() = ctx.clock.tick

    /**
     * Spawns [count] entities with deterministic, well-spread starting state.
     *
     * Every entity carries all three components, and every third one is [Link]ed to another,
     * so a restore has entity references to get right and not only scalars.
     */
    fun spawn(count: Int): List<NetId> {
        val ids = ArrayList<NetId>(count)
        repeat(count) {
            val entity = world.entity {
                it += Movement()
                it += Vitals()
                it += Link()
            }
            val netId = netIds.allocate(entity)
            populate(entity, netId.index)
            ids += netId
        }
        // A second pass, because a link can only point at an id that has been allocated.
        for (index in ids.indices) {
            if (index % 3 != 0) continue
            val entity = checkNotNull(netIds.resolveOrNull(ids[index]))
            with(world) { entity[Link] }.target = ids[(index + 1) % ids.size]
        }
        return ids
    }

    /**
     * Frees the ids at [freeOrder]'s positions and spawns that many replacements.
     *
     * The replacements take the recycled ids straight back off the free list, and every one of
     * them is populated from the **id it was given** rather than from the order it was created
     * in — which is what lets two worlds handed opposite [freeOrder]s end up holding an
     * identical roster with identical state, while their Fleks entity ids, their family bags
     * and their id free lists differ. That difference is the only thing a "the hash ignores
     * creation order" test has to work with.
     *
     * @return the roster after the churn, indexed by [NetId.index] exactly as [spawn]'s is.
     */
    fun churn(ids: List<NetId>, freeOrder: List<Int>, decoys: Int = 0): List<NetId> {
        val roster = ids.toMutableList()
        for (position in freeOrder) destroy(roster[position])
        // Fleks entities that never get a NetId: created and removed again, they shuffle Fleks'
        // own entity-id recycling without touching the roster, the ids or a single captured
        // field. Without them the two churns are mirror images and Fleks hands the recycled
        // entity ids back in exactly the order that cancels the difference out.
        val scratch = List(decoys) { world.entity { } }
        for (decoy in scratch) world -= decoy
        repeat(freeOrder.size) {
            val entity = world.entity {
                it += Movement()
                it += Vitals()
                it += Link()
            }
            val netId = netIds.allocate(entity)
            populate(entity, netId.index)
            roster[netId.index] = netId
        }
        return roster
    }

    /**
     * The Fleks entity id behind each of [roster], in roster order.
     *
     * The "underneath" a capture is supposed to hide. A test that asserts two worlds hash the
     * same has to show they differ somewhere first, or it is asserting hash(X) == hash(X).
     */
    fun entityIdsInRosterOrder(roster: List<NetId>): List<Int> =
        roster.map { checkNotNull(netIds.resolveOrNull(it)) { "$it is not live" }.id }

    /** Writes the state [spawn] gives the entity holding the id at [index]. */
    private fun populate(entity: Entity, index: Int) {
        with(world) {
            val movement = entity[Movement]
            movement.position.x = index * 0.5f
            movement.position.y = index * -0.25f
            movement.velocity.x = 1f + (index % 7) * 0.125f
            movement.velocity.y = -1f + (index % 5) * 0.25f
            val vitals = entity[Vitals]
            vitals.health = 100f - (index % 13)
            vitals.shieldCharges = index % 4
            vitals.invulnerable = index % 11 == 0
            entity[Link].squad = if (index % 2 == 0) SQUAD_RED else SQUAD_BLUE
        }
    }

    /** The `x` of [netId]'s [Movement], for tests that need one scalar out of the world. */
    fun positionXOf(netId: NetId): Float {
        val entity = checkNotNull(netIds.resolveOrNull(netId)) { "$netId is not live" }
        return with(world) { entity[Movement] }.position.x
    }

    /** Destroys [netId]'s entity the way a gameplay system would. */
    fun destroy(netId: NetId) {
        val entity = checkNotNull(netIds.resolveOrNull(netId)) { "$netId is not live" }
        world -= entity
        netIds.free(netId)
    }

    /** Spawns one entity carrying only [Movement], for tests about component presence. */
    fun spawnMovementOnly(x: Float): NetId {
        val entity = world.entity {
            it += Movement(position = Vec2(x, x))
        }
        return netIds.allocate(entity)
    }

    /** Runs one whole tick: barrier drain, systems, clock. */
    fun step(): Unit = simulation.step()

    /** Captures into a caller-owned slot, as the ring does. */
    fun captureInto(slot: WorldSnapshot): Unit = service.captureInto(slot)

    /** The hash of the world as it stands, via a scratch capture. */
    fun hashNow(scratch: WorldSnapshot): Long {
        service.captureInto(scratch)
        return WorldHasher.hash(scratch.fields)
    }

    companion object {
        /** Interned, so the object column holds a value with a stable `hashCode`. */
        const val SQUAD_RED: String = "squad.red"
        const val SQUAD_BLUE: String = "squad.blue"
    }
}

/**
 * Integrates position by velocity and bounces off a box.
 *
 * Allocation-free by construction: the family's [Family.entities] bag is walked by index, so
 * there is no iterator and no lambda capturing the system. That matters because
 * `TickLoopBudgetTest` asserts the assembled loop allocates nothing in steady state, and a
 * `forEach` here would put one closure per tick on that budget.
 */
internal class MovementSystem : SimSystem() {

    private val family: Family = world.family { all(Movement) }

    override fun onTick() {
        val dt = ctx.clock.dt
        val entities = family.entities
        var index = 0
        while (index < entities.size) {
            val entity: Entity = entities[index]
            val movement = entity[Movement]
            movement.position.x += movement.velocity.x * dt
            movement.position.y += movement.velocity.y * dt
            if (movement.position.x > BOUND || movement.position.x < -BOUND) {
                movement.velocity.x = -movement.velocity.x
                movement.lastGroundedTick = tick
            }
            if (movement.position.y > BOUND || movement.position.y < -BOUND) {
                movement.velocity.y = -movement.velocity.y
                movement.lastGroundedTick = tick
            }
            index++
        }
    }

    private companion object {
        const val BOUND: Float = 32f
    }
}

/**
 * Bleeds health from the seeded combat stream.
 *
 * Its only job is to make the simulation depend on `RngService`, so that a restore which
 * forgot to bring the random streams back diverges on the very next tick — which is exactly
 * the failure the snapshot-equivalence gate exists to catch.
 */
internal class VitalsSystem : SimSystem() {

    private val family: Family = world.family { all(Vitals) }

    override fun onTick() {
        val entities = family.entities
        var index = 0
        while (index < entities.size) {
            val vitals = entities[index][Vitals]
            vitals.health -= ctx.rng.nextFloat(RngStream.Combat) * BLEED
            if (vitals.health <= 0f) {
                vitals.health += 100f
                vitals.damageDealt++
                vitals.respawnTick = tick
            }
            index++
        }
    }

    private companion object {
        const val BLEED: Float = 0.5f
    }
}

/** A [GameContext] with the fixture services, for tests that need no world. */
internal fun snapshotContext(seed: Long = 1L): GameContext = testGameContext(seed = seed) {
    rng = DefaultRngService(seed)
}
