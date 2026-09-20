package dev.wildware.hollow.desktop

import dev.wildware.hollow.net.HollowClient
import dev.wildware.hollow.net.HollowNet
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.net.transport.DisconnectReason
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.UdpConnectionListener
import dev.wildware.udea.net.transport.UdpTransport
import io.ktor.network.sockets.InetSocketAddress
import java.security.SecureRandom

/**
 * `sh gradlew :hollow:desktop:runClient --args="host [port]"` or `--args="join <host[:port]>"`: a
 * window on the clearing as a networked client (issue #249).
 *
 * - `host [port]` is a listen server: this process runs the authoritative server on a UDP port
 *   (27025 by default) and joins it as the local player, and another machine can `join` it.
 *   `:hollow:desktop:run` is this, on the default port.
 * - `join <host[:port]>` joins a server somewhere else - another player's `host`, or `runServer`.
 *
 * Either way the window draws the client's world: the clearing it loaded itself, with the server's
 * entities replicated onto it.
 */
public object HollowClientMain {

    @JvmStatic
    public fun main(args: Array<String>) {
        when (val mode = args.firstOrNull()?.trim()?.lowercase()) {
            "host" -> play(serving = UdpServing(portOf(args.getOrNull(1)), HollowLaunch.levelBytes()), joinTo = null)
            "join" -> play(serving = null, joinTo = addressOf(args.getOrNull(1)))
            else -> throw IllegalArgumentException("unknown mode '$mode'; expected host [port] or join <host[:port]>")
        }
    }

    /** Hosts on [HollowNet.DEFAULT_PORT] and plays: what `run` does. */
    internal fun host() {
        play(serving = UdpServing(HollowNet.DEFAULT_PORT, HollowLaunch.levelBytes()), joinTo = null)
    }

    private fun play(serving: UdpServing?, joinTo: InetSocketAddress?) {
        val server = joinTo ?: InetSocketAddress(LOOPBACK, checkNotNull(serving).address.port)
        if (serving != null) {
            println("[hollow.client] hosting on ${serving.address}; another player runs:")
            println("[hollow.client]   sh gradlew :hollow:desktop:runClient --args=\"join <this machine>:${serving.address.port}\"")
        }
        println("[hollow.client] connecting to $server")
        val started = try {
            HollowLaunch.start(RenderMode.Windowed)
        } catch (failure: RuntimeException) {
            serving?.close()
            throw failure
        }
        val clock = ManualClock()
        var dropped: DisconnectReason? = null
        val socket = UdpTransport.client(
            serverAddress = server,
            clientSalt = SecureRandom().nextLong(),
            clock = clock,
            protoHash = HollowNet.protocol().protoHash,
            config = UdpServing.config(),
            listener = object : UdpConnectionListener {
                override fun onDisconnected(peer: PeerId, reason: DisconnectReason) {
                    dropped = reason
                }
            },
        )
        val client = HollowClient(PeerId.client(1), socket, started.host, UdpServing.SESSION_MTU)
        var announced = false
        var frames = 0L
        try {
            started.backend.drive { _ ->
                val tick = clock.advance()
                serving?.pump()
                socket.flush()
                socket.poll { _, buffer, offset, length -> client.onPacket(buffer, offset, length) }
                client.tick(tick)
                started.host.frame(0f)
                if (socket.isConnected && !announced) {
                    println("[hollow.client] connected as ${socket.localPeer}; ${started.host.world.numEntities} entities")
                    announced = true
                }
                if (announced && ++frames % REPORT_FRAMES == 0L) {
                    // What only the wire can have delivered: the server's tick, and the entities
                    // its snapshots have put in this client's replica store.
                    println(
                        "[hollow.client] server tick ${client.serverTick.value}; " +
                            "${client.replication.world.liveNetIds().size} entities replicated; ${client.applier}",
                    )
                }
                dropped?.let {
                    println("[hollow.client] disconnected: $it")
                    dropped = null
                }
            }
            started.backend.awaitExit()
        } finally {
            client.close()
            serving?.close()
            started.backend.close()
        }
    }

    private fun portOf(raw: String?): Int =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("'$it' is not a port number")
        } ?: HollowNet.DEFAULT_PORT

    private fun addressOf(raw: String?): InetSocketAddress {
        val text = raw?.trim().orEmpty()
        require(text.isNotEmpty()) { "join needs an address: --args=\"join <host[:port]>\"" }
        val colon = text.lastIndexOf(':')
        if (colon < 0) return InetSocketAddress(text, HollowNet.DEFAULT_PORT)
        val port = text.substring(colon + 1).toIntOrNull()
            ?: throw IllegalArgumentException("'${text.substring(colon + 1)}' is not a port number")
        return InetSocketAddress(text.substring(0, colon), port)
    }

    private const val LOOPBACK: String = "127.0.0.1"

    /** Frames between the client's progress lines: ten seconds at 60Hz. */
    private const val REPORT_FRAMES: Long = 600L
}
