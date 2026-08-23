package dev.wildware.moba.net

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.net.wire.ReplicaStore

/**
 * Pushes a [ReplicaStore] onto a live Fleks world, so a client renders replicated state.
 *
 * ## The seam this fills
 *
 * `SnapshotReader` deliberately stops at a [ReplicaStore]: `udea-net` will not take an ECS
 * dependency, and its own KDoc says so - "getting from there onto a Fleks world is
 * `Replicator.apply`, which needs a live component instance and therefore a world". This is
 * that step, and it is the one piece a game has to supply. It is written against
 * [ComponentRegistry] and nothing `moba`-specific, so a second game would copy it unchanged;
 * it lives here rather than in the engine because nothing yet says which of the two ways a
 * client can hold replicated state - a store, or components - the engine should bless.
 *
 * ## Why the mutation goes through the barrier
 *
 * Creating and destroying entities is a structural change to a Fleks world, and spec 3.3 gives
 * exactly one way to make one from outside a system. The alternative - mutating during the
 * drain-free window between ticks - happens to work today and would break the first time a
 * client grew a system that iterated while this ran.
 *
 * ## What a create looks like
 *
 * An entity the store has and the world does not is created empty and *bound to the server's
 * [NetId]*, generation included. That binding is the whole of client-side identity: a
 * `Projectile.owner` field that names `#12@3` resolves on the client to the same unit it names
 * on the server, and a stale reference reads stale on both. Components are then applied with
 * [dev.wildware.udea.core.snapshot.ReplicatedComponentType.applyOnto], which adds the component
 * if the entity does not carry it and otherwise writes **in place** - so the `Vec2` the renderer
 * holds a reference to keeps its identity from one packet to the next.
 *
 * ## What is not applied
 *
 * `@Sim`-only fields. They are not on the wire at all (`Replicator.netMask` excludes them), so
 * a component created here starts with its declared defaults for those fields and keeps them.
 * That is correct rather than a gap - a client has no business holding the server's bot
 * blackboard - but it does mean a client's world is not byte-identical to the server's, and any
 * agreement check between the two must be over the `@Net` set. [NetStateProbe] is that check.
 */
public class ReplicaApplier(

    /** The component types the session shares. Must be the registry the store was read into. */
    public val registry: ComponentRegistry,

    private val world: World,
    private val netIds: NetIdIndex,
    private val barrier: SimBarrier,
    private val ctx: GameContext,
) {

    /** Raw [NetId]s this applier has bound into the world, so it knows what to destroy. */
    private var bound = IntArray(INITIAL_CAPACITY)
    private var boundCount = 0

    /** Entities created because the store held an id the world did not. */
    public var entitiesCreated: Long = 0L
        private set

    /** Entities destroyed because the store stopped holding their id. */
    public var entitiesDestroyed: Long = 0L
        private set

    /** Component writes performed. */
    public var componentsApplied: Long = 0L
        private set

    /** How many ids this applier currently believes in. Equals the store's live count. */
    public val liveCount: Int get() = boundCount

    /**
     * Applies [store] to the world at a tick boundary.
     *
     * Submitted and drained here rather than left for the next `Simulation.step()`, for the
     * reason `SnapshotTimeTravel.restoreNearestAtOrBefore` forces its own drain: a caller that
     * applied and then rendered would draw the frame *before* the state it had just received.
     */
    public fun apply(store: ReplicaStore) {
        require(store.registry === registry) {
            "this applier is built over $registry and was handed a store over ${store.registry}"
        }
        barrier.submit(Apply(store))
        barrier.drain(world, ctx)
    }

    private inner class Apply(private val store: ReplicaStore) : BarrierAction {

        override val label: String = "apply replicated state"

        override fun apply(world: World, ctx: GameContext) {
            destroyGone()
            createAndUpdate()
        }

        /**
         * Destroys every id this applier bound that the store no longer holds.
         *
         * Walks the applier's own roster and not the world's, because the world may legitimately
         * hold entities nobody replicated - a purely local effect, a HUD marker - and destroying
         * those would make a client unable to have anything of its own.
         */
        private fun destroyGone() {
            var write = 0
            for (read in 0 until boundCount) {
                val netId = NetId.ofRaw(bound[read])
                if (netId in store) {
                    bound[write] = bound[read]
                    write++
                    continue
                }
                val entity = netIds.resolveOrNull(netId)
                if (entity != null) {
                    world -= entity
                    netIds.free(netId)
                    entitiesDestroyed++
                }
            }
            boundCount = write
        }

        private fun createAndUpdate() {
            for (row in 0 until store.rowHighWater) {
                if (!store.isLive(row)) continue
                val netId = store.netIdAt(row)
                var entity = netIds.resolveOrNull(netId)
                if (entity == null) {
                    entity = world.entity { }
                    netIds.bind(entity, netId)
                    record(netId)
                    entitiesCreated++
                }
                for (component in 0 until registry.size) {
                    val slot = store.slotOf(row, component)
                    if (slot == ReplicaStore.ABSENT) continue
                    registry.typeAt(component)
                        .applyOnto(world, entity, store.storeAt(component), slot)
                    componentsApplied++
                }
            }
        }

        private fun record(netId: NetId) {
            if (boundCount == bound.size) bound = bound.copyOf(bound.size * 2)
            bound[boundCount] = netId.raw
            boundCount++
        }
    }

    override fun toString(): String =
        "ReplicaApplier(live=$boundCount, created=$entitiesCreated, destroyed=$entitiesDestroyed)"

    private companion object {
        const val INITIAL_CAPACITY: Int = 256
    }
}
