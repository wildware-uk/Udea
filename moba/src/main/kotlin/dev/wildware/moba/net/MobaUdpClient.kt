package dev.wildware.moba.net

import dev.wildware.udea.net.transport.DatagramSink
import dev.wildware.udea.net.transport.DisconnectReason
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.UdpConnectionListener
import dev.wildware.udea.net.transport.UdpTransport
import java.util.concurrent.LinkedBlockingQueue

/**
 * A `moba` client in its own operating-system process, holding a world it did not simulate.
 *
 * It seeds no level. Every entity it reports was put in its Fleks world by [ReplicaApplier] off
 * a datagram that crossed a real socket, which is what makes the unit count and the hash it
 * prints a statement about replication rather than about two copies of the same level file.
 *
 * The only thing it is told is a port number and its own salt. The protocol hash it checks
 * against the server's is derived from its **own** component registry, so two builds that
 * disagree about a component are refused by name rather than discovered as garbage in a field.
 *
 * `args`: `<port> <maxTicks> <clientSalt> <perfect|lossy> <drive|watch>`
 */
public object MobaUdpClient {

    @JvmStatic
    public fun main(args: Array<String>) {
        val port = args[0].toInt()
        val maxTicks = args.getOrNull(1)?.toInt() ?: DEFAULT_MAX_TICKS
        val salt = args.getOrNull(2)?.toLong() ?: 1L
        val conditions = MobaUdpProof.conditionsOf(args.getOrNull(3) ?: "perfect")
        val drives = (args.getOrNull(4) ?: "watch") == "drive"

        val clock = ManualClock()
        val late = LateTransport()
        val session = MobaClientSession(PeerId.client(1), late, mtu = MobaUdpProof.MTU)
        var connected = false
        var disconnect: DisconnectReason? = null
        val socket = UdpTransport.client(
            serverAddress = MobaUdpProof.loopback(port),
            clientSalt = salt,
            clock = clock,
            protoHash = session.protocol.protoHash,
            config = MobaUdpProof.config().copy(mtu = LINK_MTU),
            listener = object : UdpConnectionListener {
                override fun onConnected(peer: PeerId) {
                    connected = true
                }

                override fun onDisconnected(peer: PeerId, reason: DisconnectReason) {
                    disconnect = reason
                }
            },
        )
        val link = MobaUdpProof.impair(socket, clock, conditions, MobaUdpProof.SEED + salt)
        late.target = link

        val sink = DatagramSink { _, buffer, offset, length ->
            session.onPacket(buffer, offset, length)
        }

        MobaUdpProof.say("PROTO ${session.protocol.protoHash}")

        val commands = LinkedBlockingQueue<String>()
        Thread { generateSequence(::readLine).forEach(commands::put) }
            .apply { isDaemon = true }
            .start()

        var ticks = 0
        var announced = false
        var stopped = false
        var deadline = System.nanoTime()
        try {
            while (ticks < maxTicks && !stopped && disconnect == null) {
                val tick = clock.advance()
                socket.flush()
                MobaUdpProof.flushImpairment(link)
                link.poll(sink)

                if (connected && !announced) {
                    MobaUdpProof.say("CONNECTED tick=${tick.value}")
                    announced = true
                }
                if (connected) {
                    // Input, and only input. A walk on the one client that drives the level's
                    // player unit; the spectator sends an idle command so the server's jitter
                    // buffer is drained rather than reported as flooding.
                    val move = if (drives) MobaUdpProof.walk(tick) else 0f
                    session.tick(tick, session.command(tick, moveX = move, moveY = 0f))
                }
                ticks++

                while (true) {
                    val command = commands.poll() ?: break
                    when {
                        command.trim() == "stop" -> stopped = true
                        command.trim() == "state" -> report(session)
                    }
                }

                deadline += MobaUdpProof.TICK_NANOS
                val remaining = deadline - System.nanoTime()
                if (remaining > 0L) {
                    Thread.sleep(remaining / NANOS_PER_MILLI, (remaining % NANOS_PER_MILLI).toInt())
                }
            }
            report(session)
            val stats = socket.stats(PeerId.SERVER)
            MobaUdpProof.say(
                "RESULT ticks=$ticks connected=${if (connected) 1 else 0} " +
                    "disconnect=${disconnect ?: "none"} " +
                    "sent=${stats.packetsSent} recv=${stats.packetsReceived} " +
                    "dropped=${stats.packetsDropped}",
            )
            MobaUdpProof.say("DONE")
        } finally {
            session.close()
        }
    }

    /**
     * One reading of this client's own world, at the tick it actually holds.
     *
     * The tick is the client's, not the server's, and the parent asks the server for the same
     * one. A client can only ever hold a tick the server has already left.
     */
    private fun report(session: MobaClientSession) {
        val fields = session.state().fields
        MobaUdpProof.say(
            "STATE tick=${session.serverTick.value} units=${session.unitCount()} " +
                "entities=${NetStateProbe.entityCount(fields)} " +
                "applied=${session.applied} stale=${session.staleDropped} " +
                "unitHash=${MobaUdpProof.hex(NetStateProbe.unitHash(fields))}",
        )
        MobaUdpProof.say("COMPHASH tick=${session.serverTick.value} ${MobaUdpProof.componentLine(fields)}")
    }

    /** What a real link carries; the transport fragments and reassembles anything larger. */
    private const val LINK_MTU: Int = 1200

    /** Ten seconds at 60Hz. */
    private const val DEFAULT_MAX_TICKS: Int = 600

    private const val NANOS_PER_MILLI: Long = 1_000_000L
}
