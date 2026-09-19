package dev.wildware.hollow.net

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.net.wire.ReplicaStore

/**
 * Puts a client's [ReplicaStore] onto its world: the step `udea-net` stops short of, because it takes
 * no ECS dependency. `moba`'s `ReplicaApplier` is the same step for a client whose world starts empty;
 * a Hollow client's does not, and that is the whole of the difference.
 *
 * ## A world that already holds the level
 *
 * A Hollow client loads the clearing itself (see [HollowNet]), so most of what the server replicates
 * is already in its world under the same `NetId`, placed by the same level file. So:
 *
 * - an entity the store holds and the world does not - one the server spawned - is created and bound
 *   to the server's `NetId`, generation included, and remembered;
 * - an entity this applier created that the store no longer holds is destroyed. Only those: a level
 *   entity is not destroyed because the store has not mentioned it yet, since a client that joined a
 *   moment ago holds a store the server's bandwidth budget has only part-filled;
 * - a component the store holds is written onto the entity, server's values over the level's, and
 *   one it has dropped is removed.
 *
 * ## Why through the barrier
 *
 * Creating and destroying entities is a structural change to a Fleks world, and spec 3.3 gives one
 * way to make one from outside a system. It is drained here, at once, so a frame drawn after [apply]
 * shows what was just received.
 */
public class HollowReplicaApplier(
    /** The component types the session shares: the registry the store was read into. */
    public val registry: ComponentRegistry,
    private val world: World,
    private val netIds: NetIdIndex,
    private val barrier: SimBarrier,
    private val ctx: GameContext,
) {

    /** Raw `NetId`s this applier created, so it knows which it may destroy. */
    private var created = IntArray(INITIAL_CAPACITY)
    private var createdCount = 0

    /** Entities created because the store held an id the world did not. */
    public var entitiesCreated: Long = 0L
        private set

    /** Entities destroyed because the store stopped holding an id this applier created. */
    public var entitiesDestroyed: Long = 0L
        private set

    /** Component writes performed. */
    public var componentsApplied: Long = 0L
        private set

    /** Applies [store] to the world now, between ticks. */
    public fun apply(store: ReplicaStore) {
        require(store.registry === registry) { "this applier is built over $registry and was handed a store over ${store.registry}" }
        barrier.submit(Apply(store))
        barrier.drain(world, ctx)
    }

    private inner class Apply(private val store: ReplicaStore) : BarrierAction {

        override val label: String = "apply replicated state"

        override fun apply(world: World, ctx: GameContext) {
            destroyGone()
            for (row in 0 until store.rowHighWater) {
                if (store.isLive(row)) applyRow(row)
            }
        }

        private fun destroyGone() {
            var write = 0
            for (read in 0 until createdCount) {
                val netId = NetId.ofRaw(created[read])
                if (netId in store) {
                    created[write++] = created[read]
                    continue
                }
                val entity = netIds.resolveOrNull(netId) ?: continue
                world -= entity
                netIds.free(netId)
                entitiesDestroyed++
            }
            createdCount = write
        }

        private fun applyRow(row: Int) {
            val netId = store.netIdAt(row)
            var entity = netIds.resolveOrNull(netId)
            if (entity == null) {
                entity = world.entity { }
                netIds.bind(entity, netId)
                remember(netId)
                entitiesCreated++
            }
            for (component in 0 until registry.size) {
                val type = registry.typeAt(component)
                val present = type.isPresent(world, entity)
                val slot = store.slotOf(row, component)
                if (slot == ReplicaStore.ABSENT) {
                    if (present) type.removeFrom(world, entity)
                    continue
                }
                type.applyOnto(world, entity, store.storeAt(component), slot)
                componentsApplied++
            }
        }

        private fun remember(netId: NetId) {
            if (createdCount == created.size) created = created.copyOf(created.size * 2)
            created[createdCount++] = netId.raw
        }
    }

    override fun toString(): String =
        "HollowReplicaApplier(created=$entitiesCreated, destroyed=$entitiesDestroyed, applied=$componentsApplied)"

    private companion object {
        const val INITIAL_CAPACITY: Int = 64
    }
}
