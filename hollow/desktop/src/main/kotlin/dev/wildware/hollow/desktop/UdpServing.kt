package dev.wildware.hollow.desktop

import dev.wildware.hollow.net.HollowNet
import dev.wildware.hollow.net.HollowServer
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.transport.ConnectionSecret
import dev.wildware.udea.net.transport.DisconnectReason
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.UdpConfig
import dev.wildware.udea.net.transport.UdpConnectionListener
import dev.wildware.udea.net.transport.UdpTransport
import io.ktor.network.sockets.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A [HollowServer] listening on a UDP port: the dedicated server's whole network, and the host half
 * of a listen server. One [pump] is one server tick - the clock, the socket, who joined or left,
 * then the simulation and the sends.
 *
 * The protocol is derived before the server exists, from [HollowNet.protocol], because the socket
 * needs its hash to refuse a client of another build and the server needs the socket to send on.
 */
internal class UdpServing(port: Int, level: ByteArray) : AutoCloseable {

    private val clock = ManualClock()

    /** Joins (`true`) and leaves (`false`), from the socket's callbacks, applied at the next [pump]. */
    private val arrivals = ConcurrentLinkedQueue<Pair<PeerId, Boolean>>()

    private val socket: UdpTransport = UdpTransport.server(
        bindAddress = InetSocketAddress(ANY_ADDRESS, port),
        clock = clock,
        secret = ConnectionSecret(ByteArray(ConnectionSecret.MIN_KEY_BYTES).also(SecureRandom()::nextBytes)),
        protoHash = HollowNet.protocol().protoHash,
        config = config(),
        listener = object : UdpConnectionListener {
            override fun onConnected(peer: PeerId) {
                arrivals += peer to true
            }

            override fun onDisconnected(peer: PeerId, reason: DisconnectReason) {
                arrivals += peer to false
            }
        },
    )

    /** The authoritative game. */
    val server: HollowServer = HollowServer(socket, BandwidthBudget(SESSION_MTU), SESSION_MTU, level)

    init {
        check(server.protocol.protoHash == HollowNet.protocol().protoHash) {
            "the server built a different protocol from the one its socket was bound with"
        }
    }

    /** The address the socket is bound to; its port is the one to join. */
    val address: InetSocketAddress get() = socket.localAddress

    /** One server tick. */
    fun pump() {
        clock.advance()
        socket.flush()
        socket.poll { from, buffer, offset, length -> server.onPacket(from, buffer, offset, length) }
        while (true) {
            val (peer, joined) = arrivals.poll() ?: break
            if (joined) {
                server.addClient(peer)
                println("[hollow.server] $peer joined; ${server.clients().size} connected")
            } else {
                server.removeClient(peer)
                println("[hollow.server] $peer left; ${server.clients().size} connected")
            }
        }
        server.tick()
    }

    override fun close() {
        server.close()
    }

    override fun toString(): String = "UdpServing($address, $server)"

    companion object {
        /**
         * What one replication message may carry. Larger than a datagram: the UDP transport splits
         * a message across datagrams of [LINK_MTU], so the clearing reaches a client in a few ticks.
         */
        const val SESSION_MTU: Int = 16384

        /** The largest datagram put on the wire. */
        const val LINK_MTU: Int = 1200

        const val MAX_CLIENTS: Int = 4

        /** Every interface, so another machine can join. */
        private const val ANY_ADDRESS: String = "0.0.0.0"

        fun config(): UdpConfig = UdpConfig(mtu = LINK_MTU, maxClients = MAX_CLIENTS)
    }
}
