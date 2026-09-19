package dev.wildware.udea.net.replication

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.wire.ReplicaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A server moves and turns a 3D entity, and a client's `Transform3D` follows it (issue #246).
 *
 * Driven over the real stack - the generated `Transform3DReplicator`, `udea-core`'s own
 * `Transform3D.snapshotType()`, the server's ring, the in-memory network with a fixed latency - and
 * finished the way a client finishes: the received store is applied onto a live Fleks world with
 * `ReplicatedComponentType.applyOnto`, which is what `moba`'s `ReplicaApplier` does, and the
 * assertions read that world's component rather than the store.
 *
 * Before issue #246 every field was `@Sim`, the net mask was empty, and the client's entity sat at
 * the origin for ever while the server's walked away.
 */
class Transform3DReplicationTest {

    private val registry = ComponentRegistry(listOf(Transform3D.snapshotType()))

    @Test
    fun `a client's transform follows a moving, turning server entity within the link's delay`() {
        val run = MovingRun()
        val lags = ArrayList<Long>()
        repeat(TICKS) {
            run.session.step(1)
            val client = run.session.clients.single()
            if (client.applied == 0L) return@repeat
            val applied = run.clientTransform() ?: return@repeat
            // Where the server had it at the tick the client holds: the same function of the tick
            // the server wrote, so this compares against a value the test did not read off the wire.
            val expected = run.poseAt(client.serverTick)
            assertEquals(expected, listOf(applied.x, applied.y, applied.z, applied.rotationZ), "at ${client.serverTick}")
            lags += run.serverTick().value - client.serverTick.value
        }

        val client = run.session.clients.single()
        val applied = run.clientTransform() ?: error("the client never received the entity")
        assertTrue(applied.x > 20f, "the client's entity did not travel: x=${applied.x}")
        assertTrue(lags.size > TICKS / 2, "the client held the entity on only ${lags.size} of $TICKS ticks")
        assertTrue(
            lags.max() <= LATENCY + 1,
            "the client trailed by up to ${lags.max()} ticks on a ${LATENCY}-tick link: $lags",
        )
        assertTrue(client.applied > TICKS / 2, "the client applied only ${client.applied} packets")
    }

    /**
     * The case that put pitch, roll and scale on the wire too.
     *
     * `applyOnto` writes a component through `allMask`, so a field the client never received is
     * written from a column the client never filled. With only position and heading `@Net`, a
     * server entity at scale 2 arrived on the client at scale 0 - drawn at no size at all.
     */
    @Test
    fun `a client's transform carries the server's pitch, roll and scale, not zeros`() {
        val run = MovingRun()
        run.session.step(20)

        val applied = run.clientTransform() ?: error("the client never received the entity")
        assertEquals(
            listOf(PITCH, ROLL, SCALE_X, SCALE_Y, SCALE_Z),
            listOf(applied.rotationX, applied.rotationY, applied.scaleX, applied.scaleY, applied.scaleZ),
        )
    }

    /** One server entity moving on a fixed schedule, one client, and a live client world. */
    private inner class MovingRun {

        var netId: NetId = NetId.NONE

        val session: ReplicationSession = ReplicationSession(
            conditions = NetConditions(latencyTicks = LATENCY),
            registry = registry,
            mutate = { tick -> move(tick) },
        )

        private val clientWorld: World = configureWorld { }
        private val clientIds = NetIdIndex(capacity = 16, entityCapacity = 16)

        private fun move(tick: Tick) {
            val world = session.world
            if (netId == NetId.NONE) {
                val entity = world.world.entity {
                    it += Transform3D(
                        rotationX = PITCH, rotationY = ROLL,
                        scaleX = SCALE_X, scaleY = SCALE_Y, scaleZ = SCALE_Z,
                    )
                }
                netId = world.netIds.allocate(entity)
            }
            // The server's clock is one ahead of the harness tick once `captureTick` has stepped
            // it, and that is the tick the capture is stamped with - so write the pose for it.
            val stamp = Tick(world.ctx.clock.tick.value + 1)
            val pose = poseAt(stamp)
            with(world.world) {
                val t = checkNotNull(world.netIds.resolveOrNull(netId))[Transform3D]
                t.x = pose[0]
                t.y = pose[1]
                t.z = pose[2]
                t.rotationZ = pose[3]
            }
        }

        /** `x`, `y`, `z` and heading at [tick]: a straight walk uphill, turning as it goes. */
        fun poseAt(tick: Tick): List<Float> {
            val t = tick.value.toFloat()
            return listOf(t * 0.5f, t * -0.25f, t * 0.125f, t * 0.03125f)
        }

        fun serverTick(): Tick = session.world.ctx.clock.tick

        /** Applies the client's received store onto [clientWorld] and returns the entity's transform. */
        fun clientTransform(): Transform3D? {
            val store = session.clients.single().world
            val row = store.rowOf(netId)
            if (row == ReplicaStore.ABSENT) return null
            val slot = store.slotOf(row, 0)
            if (slot == ReplicaStore.ABSENT) return null
            val entity: Entity = clientIds.resolveOrNull(netId)
                ?: clientWorld.entity { }.also { clientIds.bind(it, netId) }
            registry.typeAt(0).applyOnto(clientWorld, entity, store.storeAt(0), slot)
            return with(clientWorld) { entity[Transform3D] }
        }
    }

    private companion object {
        const val TICKS = 90
        const val LATENCY = 3
        const val PITCH = 0.3f
        const val ROLL = -0.2f
        const val SCALE_X = 2f
        const val SCALE_Y = 3f
        const val SCALE_Z = 4f
    }
}
