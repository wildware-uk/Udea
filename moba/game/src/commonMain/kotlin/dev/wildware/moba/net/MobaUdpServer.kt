package dev.wildware.moba.net

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.transport.DatagramSink
import dev.wildware.udea.net.transport.DisconnectReason
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.UdpConnectionListener
import dev.wildware.udea.net.transport.UdpTransport
import java.util.concurrent.LinkedBlockingQueue

/**
 * `moba` as an authoritative server on a **real socket**, in its own operating-system process.
 *
 * ## Why this exists next to `MobaServer`
 *
 * `MobaServer` is the listen server: every endpoint is a `Transport` in one JVM, which is what
 * makes a two-client agreement check deterministic and fast. It cannot show that any of it works
 * when the peers do not share a heap. A shared `ManualClock`, a shared `ByteArray`, a shared
 * class loader and a `close()` that runs in the same JVM are four ways an in-process proof can
 * pass while the real thing does not.
 *
 * So this binds a UDP socket and nothing else changes: the simulation is the same
 * [MobaHostSession] over the same `MobaGame` definition, the baselines still come out of the
 * snapshot ring that backs `time.rewind`, and the seam the socket plugs into is the `Transport`
 * constructor parameter that was already there. That is the claim the whole SPI exists to make,
 * and this is where it is cashed.
 *
 * ## The protocol on stdout, and why the parent asks for a tick
 *
 * The server captures at T and sends; the earliest a client can hold that is T + 1, and under
 * 150ms it is later still. So a hash comparison has to be made **at the tick the client actually
 * holds**, which only the client knows. The parent test reads the client's tick and asks this
 * process `hashat <tick>`; the answer comes out of the ring slot for exactly that tick, never
 * the nearest one. Comparing against the newest capture would be asserting that replication is
 * instantaneous rather than that it is correct.
 *
 * `args`: `<maxTicks> <perfect|lossy>`
 */
public object MobaUdpServer {

    @JvmStatic
    public fun main(args: Array<String>) {
        val maxTicks = args.getOrNull(0)?.toInt() ?: DEFAULT_MAX_TICKS
        val conditions = MobaUdpProof.conditionsOf(args.getOrNull(1) ?: "perfect")

        val clock = ManualClock()
        val events = ArrayList<String>()
        val late = LateTransport()
        val session = MobaHostSession(late, BandwidthBudget(MobaUdpProof.MTU), MobaUdpProof.MTU)
        val socket = UdpTransport.server(
            bindAddress = MobaUdpProof.loopback(0),
            clock = clock,
            secret = MobaUdpProof.secret(),
            protoHash = session.protocol.protoHash,
            config = MobaUdpProof.config().copy(mtu = LINK_MTU),
            listener = object : UdpConnectionListener {
                override fun onConnected(peer: PeerId) {
                    events += "CONNECT $peer"
                }

                override fun onDisconnected(peer: PeerId, reason: DisconnectReason) {
                    events += "DISCONNECT $peer $reason"
                }
            },
        )
        val link = MobaUdpProof.impair(socket, clock, conditions, MobaUdpProof.SEED)
        late.target = link

        val sink = DatagramSink { from, buffer, offset, length ->
            session.onPacket(from, buffer, offset, length)
        }

        MobaUdpProof.say("PORT ${socket.localAddress.port}")
        MobaUdpProof.say("PROTO ${session.protocol.protoHash}")
        MobaUdpProof.say("PLAYER ${session.playerId}")

        // A blocking read on a daemon thread, so the parent ends the run cleanly rather than by
        // killing a process whose counters and ring it still wants to read.
        val commands = LinkedBlockingQueue<String>()
        Thread { generateSequence(::readLine).forEach(commands::put) }
            .apply { isDaemon = true }
            .start()

        var ticks = 0
        var stopped = false
        var deadline = System.nanoTime()
        try {
            while (ticks < maxTicks && !stopped) {
                clock.advance()
                socket.flush()
                MobaUdpProof.flushImpairment(link)
                link.poll(sink)

                for (event in events) {
                    MobaUdpProof.say(event)
                    val peer = peerOf(event) ?: continue
                    // `removeClient` is the point of the second half of this proof: the transport
                    // recycles a slot id, and without forgetting the dead client's baselines the
                    // next connection into that slot is delta-encoded against state belonging to
                    // somebody who has left.
                    if (event.startsWith("CONNECT")) session.addClient(peer) else session.removeClient(peer)
                }
                events.clear()

                session.tick()
                ticks++

                while (true) {
                    val command = commands.poll() ?: break
                    when {
                        command.trim() == "stop" -> stopped = true
                        command.startsWith("hashat ") -> answer(session, command.removePrefix("hashat ").trim())
                    }
                }

                deadline += MobaUdpProof.TICK_NANOS
                val remaining = deadline - System.nanoTime()
                if (remaining > 0L) {
                    Thread.sleep(remaining / NANOS_PER_MILLI, (remaining % NANOS_PER_MILLI).toInt())
                }
            }

            val stats = socket.stats(PeerId.client(1))
            MobaUdpProof.say(
                "SUMMARY ticks=$ticks simTick=${session.tick.value} clients=${session.clients().size} " +
                    "units=${NetStateProbe.unitCount(session.host.world)} " +
                    "sent=${stats.packetsSent} sentBytes=${stats.bytesSent} " +
                    "recv=${stats.packetsReceived} recvBytes=${stats.bytesReceived} " +
                    "deferrals=${session.replication.budgetDeferrals} " +
                    "recoveries=${session.replication.baselineRecoveries}",
            )
            MobaUdpProof.say("COUNTERS ${socket.counters}")
            MobaUdpProof.say("DONE")
        } finally {
            session.close()
        }
    }

    /** Answers the ring slot for exactly [raw], or says it is gone. Never the nearest slot. */
    private fun answer(session: MobaHostSession, raw: String) {
        val tick = Tick(raw.toLong())
        val state = runCatching { session.stateAt(tick) }.getOrNull()
        if (state == null) {
            MobaUdpProof.say("HASHAT tick=${tick.value} missing")
            return
        }
        MobaUdpProof.say(
            "HASHAT tick=${tick.value} entities=${NetStateProbe.entityCount(state.fields)} " +
                "units=${NetStateProbe.unitCount(session.host.world)} " +
                "unitHash=${MobaUdpProof.hex(NetStateProbe.unitHash(state.fields))}",
        )
        MobaUdpProof.say("COMPHASH tick=${tick.value} ${MobaUdpProof.componentLine(state.fields)}")
    }

    private fun peerOf(event: String): PeerId? {
        val name = event.substringAfter(' ').substringBefore(' ')
        if (!name.startsWith("client")) return null
        return PeerId(name.removePrefix("client").toIntOrNull() ?: return null)
    }

    /** What a real link carries. Anything larger is fragmented and reassembled by the transport. */
    private const val LINK_MTU: Int = 1200

    /** A minute at 60Hz: long enough to outlive both clients and notice one died. */
    private const val DEFAULT_MAX_TICKS: Int = 3600

    private const val NANOS_PER_MILLI: Long = 1_000_000L
}
