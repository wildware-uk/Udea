package dev.wildware.udea.net.proof

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.harness.MoverReplicator
import dev.wildware.udea.net.harness.NetTestComponents
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.transport.DatagramSink
import dev.wildware.udea.net.transport.DisconnectReason
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.UdpConnectionListener
import dev.wildware.udea.net.transport.UdpTransport
import dev.wildware.udea.net.wire.ProtocolDescriptor

/**
 * The client half of the two-process proof.
 *
 * It builds the component registry and therefore the protocol hash from its own code, exactly
 * as a shipped client would, so agreeing with the server is a fact about the two builds rather
 * than something the harness arranged.
 *
 * What it checks, every packet: the replicated `Mover.x` equals the server tick the packet
 * carried. The server sets `x` to the tick it captures at, so any disagreement means the bytes
 * that crossed the socket did not describe the state that produced them — a torn fragment, a
 * misapplied delta, a baseline from the wrong tick. It reports the tally rather than asserting,
 * because the parent test is the thing that fails.
 *
 * It also sends input every other tick, which is the only thing a client is ever allowed to
 * send: there is no code path from here that writes a replicated field on the server.
 */
object UdpProofClient {

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args[0].toInt()
        val maxTicks = args.getOrNull(1)?.toInt() ?: DEFAULT_MAX_TICKS
        val clock = ManualClock()
        val registry = NetTestComponents.registry()
        val protocol = ProtocolDescriptor.of(registry)
        val moverIndex = registry.indexOf(ProofProtocol.MOVER_TYPE_ID)

        var connectedAs: PeerId? = null
        var ended: DisconnectReason? = null
        val transport = UdpTransport.client(
            serverAddress = ProofProtocol.loopback(port),
            clientSalt = CLIENT_SALT,
            clock = clock,
            protoHash = protocol.protoHash,
            config = ProofProtocol.config(maxClients = 1),
            listener = object : UdpConnectionListener {
                override fun onConnected(peer: PeerId) {
                    connectedAs = peer
                }

                override fun onDisconnected(peer: PeerId, reason: DisconnectReason) {
                    ended = reason
                }
            },
        )

        ProofProtocol.say("PROTO ${protocol.protoHash}")

        var replication: ReplicationClient? = null
        var matched = 0
        var mismatched = 0
        var lastX = Float.NaN
        var lastTick = Tick.ZERO
        var inputSeq = 0
        var tick = 0
        var deadline = System.nanoTime()

        transport.use {
            while (tick < maxTicks && ended == null) {
                clock.advance()
                transport.flush()

                val client = replication
                if (client == null) {
                    transport.poll(DISCARD)
                    if (transport.isConnected) {
                        // The peer id in the accept is what the server calls this client, and it
                        // is the only place the client learns it.
                        replication = ReplicationClient(
                            peer = transport.localPeer,
                            registry = registry,
                            protocol = protocol,
                            transport = transport,
                        )
                        ProofProtocol.say("CONNECTED ${transport.localPeer} as ${connectedAs ?: "?"}")
                    }
                } else {
                    transport.poll { _, buffer, offset, length -> client.onPacket(buffer, offset, length) }
                    val store = client.world
                    val ids = store.liveNetIds()
                    if (ids.size == 1) {
                        val row = store.rowOf(ids.single())
                        val slot = store.slotOf(row, moverIndex)
                        if (slot >= 0) {
                            lastX = store.storeAt(moverIndex).getFloat(slot, MoverReplicator.X)
                            lastTick = client.serverTick
                            if (lastX == lastTick.value.toFloat()) matched++ else mismatched++
                        }
                    }
                    client.pushInput(
                        MoveInput(
                            seq = inputSeq++ and INPUT_SEQ_MASK,
                            tick = Tick(tick.toLong()),
                            moveX = 1f,
                            moveY = 0f,
                            aim = 0f,
                            buttons = 0,
                        ),
                    )
                    client.sendTick(Tick(tick.toLong()))
                    if (tick % REPORT_EVERY == 0) {
                        ProofProtocol.say("STATE tick=${lastTick.value} x=$lastX applied=${client.applied}")
                    }
                }

                tick++
                deadline += ProofProtocol.TICK_NANOS
                val remaining = deadline - System.nanoTime()
                if (remaining > 0L) Thread.sleep(remaining / NANOS_PER_MILLI, (remaining % NANOS_PER_MILLI).toInt())
            }

            val stats = transport.stats(PeerId.SERVER)
            ProofProtocol.say(
                "RESULT matched=$matched mismatched=$mismatched lastTick=${lastTick.value} lastX=$lastX " +
                    "applied=${replication?.applied ?: 0} sent=${stats.packetsSent} " +
                    "recv=${stats.packetsReceived} ended=$ended",
            )
            ProofProtocol.say("COUNTERS ${transport.counters}")
            ProofProtocol.say("DONE")
        }
    }

    /** Everything that arrives before the replication client exists, which is nothing. */
    private val DISCARD = DatagramSink { _, _, _, _ -> }

    private const val CLIENT_SALT: Long = 0x1BADB002_DEADBEEFL

    private const val DEFAULT_MAX_TICKS: Int = 600

    /** Input sequences wrap at 16 bits, as `MoveInput` documents. */
    private const val INPUT_SEQ_MASK: Int = 0xFFFF

    private const val REPORT_EVERY: Int = 60

    private const val NANOS_PER_MILLI: Long = 1_000_000L
}
