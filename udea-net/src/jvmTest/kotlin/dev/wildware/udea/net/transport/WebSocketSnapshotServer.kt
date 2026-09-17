package dev.wildware.udea.net.transport

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.replication.ReplicationServer
import dev.wildware.udea.net.wire.ProtocolDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * A real replicating server behind a [WebSocketServerTransport]: a Fleks world, the snapshot ring
 * and a [ReplicationServer], moving [WebSocketSnapshotScenario]'s entities every tick (issue #209).
 *
 * The same class serves the in-process JVM test and, through [main], the separate JVM process the
 * Wasm client test connects to, so the two tests exercise one server rather than two that could
 * drift apart.
 */
internal class WebSocketSnapshotServer(
    port: Int = 0,
    config: WebSocketConfig = WebSocketConfig(),
) : AutoCloseable {

    val world: NetTestWorld = NetTestWorld(registry = WebSocketSnapshotScenario.registry())
    val protocol: ProtocolDescriptor = ProtocolDescriptor.of(world.registry)
    val events: RecordingListener = RecordingListener()

    private lateinit var replication: ReplicationServer

    val transport: WebSocketServerTransport = WebSocketServerTransport.start(
        port = port,
        path = WebSocketSnapshotScenario.PATH,
        protoHash = protocol.protoHash,
        config = config,
        listener = object : UdpConnectionListener {
            override fun onConnected(peer: PeerId) {
                events.onConnected(peer)
                replication.addClient(peer)
            }

            override fun onDisconnected(peer: PeerId, reason: DisconnectReason) {
                events.onDisconnected(peer, reason)
                replication.removeClient(peer)
            }
        },
    )

    private val entities: List<NetId>

    init {
        replication = ReplicationServer(world.registry, protocol, transport, world.ring)
        entities = (0 until WebSocketSnapshotScenario.ENTITIES).map { index ->
            world.spawn(
                x = WebSocketSnapshotScenario.moverX(index, world.ctx.clock.tick),
                y = WebSocketSnapshotScenario.moverY(index),
            )
        }
        check(entities.map(NetId::index) == (0 until WebSocketSnapshotScenario.ENTITIES).toList()) {
            "the scenario assumes net id indices 0 until ${WebSocketSnapshotScenario.ENTITIES}, got $entities"
        }
    }

    /** Where a client connects. */
    val url: String get() = "ws://${WebSocketServerTransport.LOOPBACK}:${transport.port}${WebSocketSnapshotScenario.PATH}"

    /** One tick: take the clients' acks, move the entities, capture, and send every client its snapshot. */
    fun step() {
        transport.poll(replication::onPacket)
        val next = world.ctx.clock.tick + 1L
        entities.forEachIndexed { index, netId -> world.mover(netId).x = WebSocketSnapshotScenario.moverX(index, next) }
        val snapshot = world.captureTick()
        check(snapshot.tick == next) { "captured ${snapshot.tick}, expected $next" }
        replication.broadcast(snapshot)
    }

    override fun close() {
        transport.close()
    }

    companion object {

        /** Roughly 60Hz. The client does not depend on the rate, only on the ticks it is told. */
        private const val TICK_MILLIS: Long = 16L

        /**
         * The server process the Wasm client test connects to.
         *
         * `args[0]` is a file the server's URL is written to, which is how the Gradle task that
         * started this process learns where to point the test; `args[1]` is how many ticks to
         * serve before exiting on its own, so a build that dies without stopping it does not leave
         * a server on the box for ever.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            val portFile = File(args[0])
            val lifetimeTicks = args[1].toLong()
            WebSocketSnapshotServer().use { server ->
                val written = File(portFile.parentFile, portFile.name + ".tmp")
                written.writeText(server.url)
                check(written.renameTo(portFile)) { "could not publish the port to $portFile" }
                println("udea websocket snapshot server listening on ${server.url}")
                runBlocking {
                    for (tick in 0 until lifetimeTicks) {
                        server.step()
                        delay(TICK_MILLIS)
                    }
                }
            }
        }
    }
}
