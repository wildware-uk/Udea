package dev.wildware.udea.core.blueprint

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.serviceKey

/**
 * Turns blueprints into entities, at a tick boundary, with the id known up front.
 *
 * ## What it replaces
 *
 * The spawn loop in `GameScreen`'s `init` (`common/UdeaGameManager.kt:191-217`). Its *behaviour*
 * is carried over unchanged — blueprint components, then the level entry's own components and
 * tags, then a defaulted `Transform` when none was supplied, then the position override — and
 * its *scheduling* is thrown away. That loop ran inside a `Gdx.app.postRunnable`: a render-thread
 * hook that does not exist headless, that fired on some unspecified later frame, and that made
 * "the world is empty right now" a state every system had to tolerate.
 *
 * Here a spawn is a [BarrierAction]. It lands at the top of the next `Simulation.step()`, before
 * any system runs, so no system ever sees half of a spawn and no system ever sees an entity that
 * did not exist at the start of its tick (spec 3.3).
 *
 * ## The id comes back immediately
 *
 * [spawn] returns a live [NetId] *before* the entity exists, reserved from [NetIdIndex.reserve]
 * and attached during the drain. That is what lets the Phase 1 demo spawn a blueprint and name
 * the result in its very next tool call without a round trip to read the world back. Until the
 * drain the id resolves to `null` — the entity genuinely does not exist yet — and
 * `NetIdIndex.forEachLive` skips it, so a snapshot taken in between holds no row for it.
 *
 * ## Threading: simulation thread only
 *
 * Every method here, [spawn] and [spawnAll] included, belongs to the simulation thread. A tool
 * call arriving on an MCP request thread is marshalled onto the loop thread before it reaches
 * this class — which is the contract `SnapshotTimeTravel` already states for `rewind` and for
 * every world read, and an agent host has to implement it for those anyway.
 *
 * It is stated here because the temptation is real and the mistake is silent. [SimBarrier.submit]
 * *is* thread-safe, so it looks as though a spawn could be queued from anywhere; but [spawn] must
 * also [NetIdIndex.reserve] the id it hands back, and `NetIdIndex` is explicitly not thread-safe
 * — the simulation thread calls `allocate`, `free`, `attach`, `bind` and `forEachLive` on the
 * same instance every tick. Guarding the reservation with a lock private to this class would not
 * help: a lock only one of two racing parties takes is not mutual exclusion, and the interleaving
 * hands one index to two entities, which is the exact aliasing the generation counter exists to
 * prevent.
 */
public class BlueprintSpawner(
    private val barrier: SimBarrier,
    private val netIds: NetIdIndex,
    /**
     * How this game places a spawned entity, or `null` for a game whose blueprints place
     * themselves.
     *
     * `null` is not a stub: a simulation with no spatial component — a pure state machine, or
     * `udea-core`'s own tests — spawns perfectly well without one. What it may not do is ask
     * for a [SpawnPosition]; see [spawnAll].
     */
    private val placement: SpawnPlacement? = null,
) {

    /**
     * Entities created by an applied spawn action.
     *
     * Simulation-thread state, like everything else here: read it from a system, a
     * [BarrierAction] or a test that drives the host itself. A plain `Long` and not `@Volatile`
     * on purpose — nothing off the simulation thread may read it, so there is no cross-thread
     * edge to establish and no impression of one to give.
     */
    public var spawnedCount: Long = 0L
        private set

    /**
     * Requests dropped because their reservation was gone by the time the batch was applied.
     *
     * Non-zero only after a rewind unwound a spawn that was still queued. Counted rather than
     * only logged, because "the entity I asked for is missing" is otherwise indistinguishable
     * from a blueprint that configures nothing.
     */
    public var droppedSpawns: Long = 0L
        private set

    /**
     * Queues one entity and returns the [NetId] it will have.
     *
     * @throws IllegalArgumentException if [position] is given and this spawner has no
     *   [SpawnPlacement]. See [spawnAll].
     */
    public fun spawn(
        blueprint: Blueprint,
        position: SpawnPosition? = null,
        overrides: SpawnOverrides? = null,
    ): NetId = spawnAll(listOf(SpawnRequest(blueprint, position, overrides))).first()

    /**
     * Queues every request as **one** barrier entry and returns their ids, in request order.
     *
     * One entry and not one per request, for two reasons. Twenty separate actions would let a
     * scene swap or a snapshot restore interleave between the third and the fourth, so a batch
     * an agent asked for as a unit could land split across two worlds. And the barrier logs and
     * continues past a throwing action, so a partial failure would be twenty independent
     * outcomes to reason about instead of one.
     *
     * @throws IllegalArgumentException if any request carries a [SpawnPosition] and this spawner
     *   has no [SpawnPlacement]. Refused here, synchronously, rather than inside the drain:
     *   `SimBarrier` logs a failing action and carries on, so a spawn that discovered this in
     *   the drain would leave the caller holding an id, believing it asked for a position, with
     *   nothing but a log line to say otherwise. Silently ignoring the position would be worse
     *   still — the entity would exist, at the wrong place, and nothing would have failed.
     */
    public fun spawnAll(requests: List<SpawnRequest>): List<NetId> {
        if (placement == null) {
            val positioned = requests.firstOrNull { it.position != null }
            require(positioned == null) {
                "spawn of ${positioned?.blueprint?.id} names position ${positioned?.position}, " +
                    "but this BlueprintSpawner was built with no SpawnPlacement and has no way " +
                    "to write it; give it one, or let the blueprint place itself"
            }
        }
        if (requests.isEmpty()) return emptyList()

        val ids = List(requests.size) { netIds.reserve() }
        barrier.submit(SpawnAction(requests, ids))
        return ids
    }

    /**
     * Creates one entity now, allocating its id as it goes, and returns it.
     *
     * For a caller that is **already** at a tick boundary and inside the mutation it owns:
     * `Scene.populate` runs inside `BarrierSceneManager`'s swap action, and queueing from there
     * would land the scene's entities one tick after the scene itself. It is the same
     * instantiation the queued path applies — same order, same placement, same defaulting — so
     * a scene and an agent cannot disagree about what a blueprint means.
     *
     * The id is [NetIdIndex.allocate]d rather than reserved, because here the entity exists
     * before anyone is told the id.
     *
     * @throws IllegalArgumentException if [request] carries a position and there is no placement.
     */
    public fun spawnNow(world: World, request: SpawnRequest): NetId {
        require(request.position == null || placement != null) {
            "spawn of ${request.blueprint.id} names a position but this BlueprintSpawner has " +
                "no SpawnPlacement"
        }
        val entity = create(world, request)
        spawnedCount++
        return netIds.allocate(entity)
    }

    override fun toString(): String =
        "BlueprintSpawner(spawned=$spawnedCount, dropped=$droppedSpawns, " +
            "placement=${placement != null})"

    /**
     * Creates the entity and applies the four steps of the old loop, in its order.
     *
     * Shared by the queued and the immediate path so the two can never drift into meaning
     * different things.
     */
    private fun create(world: World, request: SpawnRequest): Entity {
        val entity = world.entity { created ->
            request.blueprint.configure(this, created)
            request.overrides?.applyTo(this, created)
        }
        val place = placement ?: return entity
        place.defaultIfAbsent(world, entity)
        val position = request.position ?: return entity
        place.moveTo(world, entity, position.x, position.y)
        return entity
    }

    /** The named mutation [spawnAll] queues: every request in the batch, in one drain step. */
    private inner class SpawnAction(
        private val requests: List<SpawnRequest>,
        private val ids: List<NetId>,
    ) : BarrierAction {

        override val label: String
            get() = if (requests.size == 1) {
                "spawn ${requests[0].blueprint.id}"
            } else {
                "spawn ${requests.size} blueprints from ${requests[0].blueprint.id}"
            }

        /**
         * Applies every request in the batch, and lets a dead reservation cost only its own.
         *
         * The reservation is checked *before* the entity is created, not discovered by a
         * throwing `attach` afterwards. Two things went wrong when it was: the entity created
         * on line one existed with no [NetId], so `SnapshotService` — which walks
         * `NetIdIndex.forEachLive` — could neither capture it nor ever destroy it on a restore,
         * leaving a permanent orphan that every system kept ticking; and the throw escaped into
         * `SimBarrier.drain`, which logs and moves to the next *action*, so the rest of this
         * batch was never created at all and nothing counted the loss.
         *
         * A reservation is only ever gone because a rewind unwound the submission, which is a
         * legitimate outcome and not a defect — so it is dropped, counted and logged, and the
         * requests either side of it still land. `attach` is still the call that binds the id:
         * the caller was handed that exact id at submit time and may already have stored it.
         */
        override fun apply(world: World, ctx: GameContext) {
            var index = 0
            while (index < requests.size) {
                val id = ids[index]
                if (netIds.isOutstandingReservation(id)) {
                    netIds.attach(create(world, requests[index]), id)
                    spawnedCount++
                } else {
                    droppedSpawns++
                    ctx.log.warn(
                        "dropping spawn of ${requests[index].blueprint.id} at ${ctx.clock.tick}: " +
                            "its reservation $id is gone, so a rewind unwound the submission",
                    )
                }
                index++
            }
        }
    }

    public companion object {
        /**
         * The key [GameContext] exposes a spawner under.
         *
         * A [ServiceKey] and not a field on `GameContext`, and not a service `CoreModule`
         * creates either: a spawner needs a [SpawnPlacement], which names the game's spatial
         * component, and the kernel has none to name. The module that owns that component
         * registers the spawner in its `context` hook; the agent toolset reads it back through
         * [blueprints].
         */
        public val KEY: ServiceKey<BlueprintSpawner> = serviceKey("BlueprintSpawner")
    }
}

/**
 * The spawner this game was built with.
 *
 * @throws dev.wildware.udea.core.MissingServiceException if no module registered one, which
 *   means a `spawn_blueprint` tool call has nothing to call and should say so rather than
 *   silently doing nothing.
 */
public val GameContext.blueprints: BlueprintSpawner get() = this[BlueprintSpawner.KEY]

/** Registers [spawner] on the context being built. */
public fun GameContextBuilder.blueprintSpawner(spawner: BlueprintSpawner): BlueprintSpawner {
    service(BlueprintSpawner.KEY, spawner)
    return spawner
}
