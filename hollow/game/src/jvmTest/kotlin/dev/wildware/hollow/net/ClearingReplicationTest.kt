package dev.wildware.hollow.net

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.hollow.Scenery
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.net.transport.NetEndpoint
import dev.wildware.udea.net.transport.NetHarness
import dev.wildware.udea.net.transport.PeerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The clearing loads on the server and replicates to clients (issue #249's multiplayer criterion):
 * a headless server and two headless clients in one process, joined by `udea-net`'s simulated
 * network, stepped tick by tick with no socket and no sleep.
 *
 * Each client also loads the clearing itself - static level content, see [HollowNet] - so "the
 * client has the trees" alone would prove nothing about the network. What these assert is what only
 * the wire can deliver: every entity the server holds is in the client's *replica store*, which only
 * datagrams fill; and an entity the server spawns, or destroys, after the clients joined appears and
 * disappears on every client under the server's `NetId`.
 */
class ClearingReplicationTest {

    private val harness = NetHarness(clients = 2)
    // No fox waves (issue #251): this test counts the entities a client created and holds, and a
    // wave of foxes arriving part-way through would be counted with the one it spawned.
    private val server = HollowServer(harness.transport(PeerId.SERVER), waves = null)
    private val clients = harness.clientPeers().map { peer ->
        server.addClient(peer)
        HollowClient(peer, harness.transport(peer))
    }

    init {
        harness.register(
            object : NetEndpoint {
                override val peer: PeerId = PeerId.SERVER

                override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                    server.onPacket(from, buffer, offset, length)
                }

                override fun onTick(tick: Tick) = server.tick()
            },
        )
        for (client in clients) {
            harness.register(
                object : NetEndpoint {
                    override val peer: PeerId = client.peer

                    override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                        client.onPacket(buffer, offset, length)
                    }

                    override fun onTick(tick: Tick) {
                        client.tick(tick)
                    }
                },
            )
        }
    }

    @AfterTest
    fun close() {
        clients.forEach(HollowClient::close)
        server.close()
        harness.close()
    }

    @Test
    fun `both ends build one protocol`() {
        clients.forEach { assertEquals(server.protocol.protoHash, it.protocol.protoHash) }
    }

    @Test
    fun `every entity of the server's clearing reaches every client's replica store`() {
        harness.step(SETTLE_TICKS)
        val expected = netIds(server.host.world, server.host.ctx[CoreModule.NET_IDS])
        assertTrue(expected.size > MINIMUM_CLEARING, "the server's clearing has ${expected.size} entities")
        for (client in clients) {
            val store = client.replication.world
            val replicated = (0 until store.rowHighWater).filter(store::isLive).map { store.netIdAt(it).raw }.toSet()
            assertEquals(expected, replicated, "${client.peer}'s replica store")
            assertEquals(expected, netIds(client.host.world, client.host.ctx[CoreModule.NET_IDS]), "${client.peer}'s world")
        }
    }

    @Test
    fun `an entity the server spawns appears on every client, and goes when the server destroys it`() {
        harness.step(SETTLE_TICKS)
        val serverIds = server.host.ctx[CoreModule.NET_IDS]
        val spawned: Entity = server.host.world.entity { it += Transform3D(x = SPAWN_X, y = SPAWN_Y) }
        val id = serverIds.allocate(spawned)
        harness.step(SETTLE_TICKS)

        for (client in clients) {
            val entity = client.host.ctx[CoreModule.NET_IDS].resolveOrNull(id)
            assertNotNull(entity, "${client.peer} has no entity $id")
            with(client.host.world) {
                assertTrue(entity has Transform3D, "${client.peer}'s $id has no Transform3D")
                assertTrue(entity hasNo Scenery, "${client.peer}'s $id is level content, not the spawned entity")
            }
            // The entity this test spawned, and one character per seat: since issue #250 a peer
            // that joins is given a character, and that character reaches a client the same way
            // anything else the server spawns does - through a create on the wire.
            assertEquals(
                (1 + clients.size).toLong(),
                client.applier.entitiesCreated,
                "${client.peer} created",
            )
        }

        val withSpawn = clients.map { it.host.world.numEntities }
        server.host.world -= spawned
        serverIds.free(id)
        harness.step(SETTLE_TICKS)
        for ((index, client) in clients.withIndex()) {
            assertNull(client.host.ctx[CoreModule.NET_IDS].resolveOrNull(id), "${client.peer} still has $id")
            assertEquals(withSpawn[index] - 1, client.host.world.numEntities, "${client.peer}'s world kept the entity")
            assertEquals(1L, client.applier.entitiesDestroyed, "${client.peer} destroyed")
        }
    }

    @Test
    fun `every client's clearing stands where the server's does`() {
        harness.step(SETTLE_TICKS)
        val serverIds = server.host.ctx[CoreModule.NET_IDS]
        val placements = placements(server.host.world, serverIds)
        assertTrue(placements.size > MINIMUM_CLEARING)
        for (client in clients) {
            assertEquals(placements, placements(client.host.world, client.host.ctx[CoreModule.NET_IDS]), "${client.peer}")
        }
    }

    @Test
    fun `a prop the server moves moves on every client`() {
        harness.step(SETTLE_TICKS)
        val serverIds = server.host.ctx[CoreModule.NET_IDS]
        val prop = with(server.host.world) { server.host.world.family { all(Scenery, Transform3D) }.entities.first() }
        val id = serverIds.netIdOf(prop)
        with(server.host.world) {
            prop[Transform3D].x = MOVED_X
            prop[Transform3D].rotationZ = MOVED_ROTATION
        }
        harness.step(SETTLE_TICKS)
        for (client in clients) {
            val entity = checkNotNull(client.host.ctx[CoreModule.NET_IDS].resolveOrNull(id)) { "${client.peer} lost $id" }
            val at = with(client.host.world) { entity[Transform3D] }
            assertEquals(MOVED_X, at.x, "${client.peer}: $at")
            assertEquals(MOVED_ROTATION, at.rotationZ, "${client.peer}: $at")
        }
    }

    /** Every entity's `Transform3D`, by raw `NetId`, as nine numbers. */
    private fun placements(world: World, ids: NetIdIndex): Map<Int, List<Float>> = buildMap {
        world.forEach { entity ->
            val id = ids.netIdOf(entity)
            if (id.isNone) return@forEach
            with(world) {
                if (entity hasNo Transform3D) return@forEach
                val at = entity[Transform3D]
                put(id.raw, listOf(at.x, at.y, at.z, at.rotationX, at.rotationY, at.rotationZ, at.scaleX, at.scaleY, at.scaleZ))
            }
        }
    }

    private fun netIds(world: World, ids: NetIdIndex): Set<Int> = buildSet {
        world.forEach { entity ->
            val id: NetId = ids.netIdOf(entity)
            if (!id.isNone) add(id.raw)
        }
    }

    private companion object {
        /** Ticks for the whole clearing to cross a 1200-byte link and be acknowledged, with room. */
        const val SETTLE_TICKS = 120

        const val MINIMUM_CLEARING = 40
        const val SPAWN_X = 3f
        const val SPAWN_Y = -2f
        const val MOVED_X = 7.5f
        const val MOVED_ROTATION = 1.25f
    }
}
