package dev.wildware.udea.net.proof

import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.replication.ReplicationServer
import dev.wildware.udea.net.transport.DatagramSink
import dev.wildware.udea.net.transport.DisconnectReason
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.UdpConnectionListener
import dev.wildware.udea.net.transport.UdpTransport
import dev.wildware.udea.net.wire.ProtocolDescriptor

/**
 * The server half of the two-process proof: a real Fleks world, replicated over a real socket.
 *
 * Nothing here is a stand-in. The world is [NetTestWorld] — real `SnapshotService`, real
 * `SnapshotRing` — and the replication is the same [ReplicationServer] every in-process test
 * drives. The only thing swapped is the [dev.wildware.udea.net.transport.Transport], which is
 * the claim the whole SPI exists to make.
 *
 * It reports what happens on stdout, one line per event, because the parent test's assertions
 * are about a *process*: that it accepted a handshake, that it kept serving through whatever
 * arrived, and that it noticed when the other end died.
 *
 * The tracked entity's `x` is set to the tick it is captured at, so a client holding server tick
 * T must see `x == T` or replication is wrong. That invariant is what makes the demo an
 * assertion rather than a screenshot.
 */
object UdpProofServer {

    @JvmStatic
    fun main(args: Array<String>) {
        val maxTicks = args.getOrNull(0)?.toInt() ?: DEFAULT_MAX_TICKS
        val clock = ManualClock()
        val world = NetTestWorld(seed = SEED)
        val protocol = ProtocolDescriptor.of(world.registry)
        val netId = world.spawn(x = 0f, y = 0f, teamId = 1)

        val events = ArrayList<String>()
        val transport = UdpTransport.server(
            bindAddress = ProofProtocol.loopback(0),
            clock = clock,
            secret = ProofProtocol.secret(),
            protoHash = protocol.protoHash,
            config = ProofProtocol.config(),
            listener = object : UdpConnectionListener {
                override fun onConnected(peer: PeerId) {
                    events += "CONNECT $peer"
                }

                override fun onDisconnected(peer: PeerId, reason: DisconnectReason) {
                    events += "DISCONNECT $peer $reason"
                }
            },
        )

        val replication = ReplicationServer(
            registry = world.registry,
            protocol = protocol,
            transport = transport,
            ring = world.ring,
        )
        // Which peers are live *now*, so a snapshot is never addressed to a slot that has
        // already gone. `ReplicationServer.removeClient` forgets the departed client's baselines
        // as well, which matters here specifically: this proof kills one client and lets the next
        // one into the same slot, and a reused slot that inherited the dead peer's acked baseline
        // ticks would be delta-encoded against a stranger's state.
        val live = LinkedHashSet<Int>()
        val sink = DatagramSink { from, buffer, offset, length ->
            replication.onPacket(from, buffer, offset, length)
        }

        ProofProtocol.say("PORT ${transport.localAddress.port}")
        ProofProtocol.say("PROTO ${protocol.protoHash}")

        var stopped = false
        val stdin = Thread {
            // A blocking read on a daemon thread, so the parent can end the run cleanly rather
            // than by killing a process whose counters it wants to read.
            generateSequence(::readLine).forEach { if (it.trim() == "stop") stopped = true }
        }
        stdin.isDaemon = true
        stdin.start()

        var tick = 0
        var deadline = System.nanoTime()
        transport.use {
            while (tick < maxTicks && !stopped) {
                clock.advance()
                transport.flush()
                transport.poll(sink)

                for (event in events) {
                    ProofProtocol.say(event)
                    val peer = peerOf(event) ?: continue
                    if (event.startsWith("CONNECT")) {
                        replication.addClient(peer)
                        live += peer.raw
                    } else {
                        live -= peer.raw
                        replication.removeClient(peer)
                    }
                }
                events.clear()

                // The value the client is going to be checked against: x is the tick it was
                // captured at, and the capture is about to advance the sim clock by one.
                world.mover(netId).x = (world.ctx.clock.tick.value + 1).toFloat()
                val snapshot = world.captureTick()
                for (raw in live) replication.send(PeerId(raw), snapshot)

                tick++
                deadline += ProofProtocol.TICK_NANOS
                val remaining = deadline - System.nanoTime()
                if (remaining > 0L) Thread.sleep(remaining / NANOS_PER_MILLI, (remaining % NANOS_PER_MILLI).toInt())
            }

            val stats = transport.stats(PeerId.client(1))
            ProofProtocol.say(
                "SUMMARY ticks=$tick simTick=${world.ctx.clock.tick.value} " +
                    "sent=${stats.packetsSent} sentBytes=${stats.bytesSent} " +
                    "recv=${stats.packetsReceived} recvBytes=${stats.bytesReceived} " +
                    "dropped=${stats.packetsDropped}",
            )
            ProofProtocol.say("COUNTERS ${transport.counters}")
            ProofProtocol.say("DONE")
        }
    }

    private fun peerOf(event: String): PeerId? {
        val name = event.substringAfter(' ').substringBefore(' ')
        if (!name.startsWith("client")) return null
        return PeerId(name.removePrefix("client").toIntOrNull() ?: return null)
    }

    private const val SEED: Long = 20_260_823L

    /** Half a minute at 60Hz: long enough to outlive the client and notice it died. */
    private const val DEFAULT_MAX_TICKS: Int = 1800

    private const val NANOS_PER_MILLI: Long = 1_000_000L
}
