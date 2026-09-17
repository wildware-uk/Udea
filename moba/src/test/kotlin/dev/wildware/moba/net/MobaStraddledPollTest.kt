package dev.wildware.moba.net

import dev.wildware.moba.level.GameUnit
import dev.wildware.udea.core.rng.SimRandom
import dev.wildware.udea.core.snapshot.WorldFieldStore
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.transport.DatagramSink
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.PacketLog
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.SimulatedTransport
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.transport.TransportStats
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The real battle, at 150ms and 5% loss, with a reader that leaves datagrams for the next poll**
 * (issue #219).
 *
 * `runUdpProof` found clients disagreeing with the server on `Position` alone, and more often once
 * #209 put a coroutine between the socket and `poll`: a burst of datagrams then lands across two
 * polls. That proof runs three processes against a wall clock, so it can say *that* a run
 * disagreed and not reproduce it. This is the same session in one thread with a manual clock, and
 * [Straddling] makes the late datagrams happen on purpose, from a seed.
 *
 * Nothing is dropped by it and nothing is reordered: it only delays. What that does is lengthen
 * the round trip, which is what reached the two defects this pins, both in `udea-net`:
 *
 *  - an index recycled while a late acknowledgement for its dead occupant was in flight stopped
 *    updating its new occupant for a round trip - in `moba`, a creep frozen at its spawn point
 *    (`RecycledIndexStallTest`);
 *  - an entity sent more times than the unacknowledged-send list holds forgot the sends it could
 *    not list, and a field that changed and changed back was omitted against the wrong baseline
 *    (`AckWindowConvergenceTest`).
 *
 * The assertion is the one `runUdpProof` makes, made on **every tick** rather than once: each
 * client's hash over the `GameUnit` roster equals the server's at the tick that client holds.
 */
class MobaStraddledPollTest {

    @Test
    fun `both clients agree with the server on every tick when datagrams straddle two polls`() {
        for (seed in SEEDS) session(seed)
    }

    private fun session(seed: Long) {
        val clock = ManualClock()
        val network = LoopbackNetwork(clock, MTU, PacketLog())
        val links = (0..CLIENTS).map { raw ->
            val impaired = SimulatedTransport(
                network.transportFor(PeerId(raw)),
                clock,
                SimRandom(seed * LINK_SEED_STEP + raw),
                PacketLog(),
                MTU,
                LOSSY,
            )
            Straddling(impaired, SimRandom(seed * READER_SEED_STEP + raw))
        }
        val server = MobaHostSession(links[0], BandwidthBudget(MTU), MTU)
        val clients = (1..CLIENTS).map { MobaClientSession(PeerId.client(it), links[it], mtu = MTU) }
        clients.forEach { server.addClient(it.peer) }
        val serverSink = DatagramSink { from, buffer, offset, length -> server.onPacket(from, buffer, offset, length) }
        val clientSinks = clients.map { client ->
            DatagramSink { _, buffer, offset, length -> client.onPacket(buffer, offset, length) }
        }

        var compared = 0
        try {
            repeat(TICKS) {
                // `NetHarness`'s order: release, receive, then simulate and send.
                val tick = clock.advance()
                links.forEach { it.inner.flush() }
                links[0].poll(serverSink)
                clients.indices.forEach { links[it + 1].poll(clientSinks[it]) }
                server.tick()
                for (client in clients) {
                    val axis = if (client.peer.raw == 1) MobaUdpProof.walk(tick) else 0f
                    client.tick(tick, client.command(tick, moveX = axis, moveY = 0f))
                }

                for (client in clients) {
                    if (client.applied == 0L) continue
                    val theirs = server.stateAt(client.serverTick).fields
                    val mine = client.state().fields
                    compared++
                    if (NetStateProbe.unitHash(theirs) == NetStateProbe.unitHash(mine)) continue
                    fail(
                        "seed $seed, tick ${tick.value}: ${client.peer} holds server tick " +
                            "${client.serverTick.value} and disagrees on the units: " +
                            unitDifferences(theirs, mine),
                    )
                }
            }
        } finally {
            clients.forEach { it.close() }
            server.close()
        }
        assertTrue(links.all { it.held > 0 }, "seed $seed: a reader never left a datagram for the next poll")
        assertTrue(compared > TICKS, "seed $seed: only $compared client readings were compared")
    }

    /** [NetStateProbe.differences], narrowed to the rows the unit hash folds. Left is the server. */
    private fun unitDifferences(server: WorldFieldStore, client: WorldFieldStore): List<String> {
        val units = unitIds(server) + unitIds(client)
        return NetStateProbe.differences(server, client, limit = Int.MAX_VALUE)
            .filter { line -> units.any { line.startsWith("$it ") } }
            .take(REPORTED)
    }

    private fun unitIds(fields: WorldFieldStore): Set<String> {
        val registry = fields.registry
        val unit = (0 until registry.size).first { registry.typeAt(it).componentClass == GameUnit::class }
        return (0 until fields.rowCount).filter { fields.isPresent(it, unit) }.map { fields.netIdAt(it).toString() }.toSet()
    }

    /**
     * A reader that stops part way through what has arrived, and hands the rest over next poll.
     *
     * Before each datagram it stops with a one-in-[STOP_ONE_IN] chance, drawn from its own seed.
     * The stop can come before the first datagram too: a loopback link at a fixed latency delivers
     * one server datagram per poll, so a reader that only ever stopped after taking one would
     * never leave anything behind.
     */
    private class Straddling(val inner: SimulatedTransport, private val rng: SimRandom) : Transport {

        private val waiting = ArrayDeque<Pair<PeerId, ByteArray>>()

        /** Polls that left at least one datagram waiting. */
        var held: Int = 0
            private set

        override val localPeer: PeerId get() = inner.localPeer

        override fun send(peer: PeerId, bytes: ByteArray, offset: Int, length: Int) =
            inner.send(peer, bytes, offset, length)

        override fun poll(sink: DatagramSink): Int {
            inner.poll { from, buffer, offset, length -> waiting.addLast(from to buffer.copyOfRange(offset, offset + length)) }
            var delivered = 0
            while (waiting.isNotEmpty()) {
                if (rng.nextInt(STOP_ONE_IN) == 0) {
                    held++
                    break
                }
                val (from, bytes) = waiting.removeFirst()
                sink.receive(from, bytes, 0, bytes.size)
                delivered++
            }
            return delivered
        }

        override fun stats(peer: PeerId): TransportStats = inner.stats(peer)

        override fun close() = inner.close()
    }

    private companion object {
        /** `MobaUdpProof.MTU`: a whole tick in one message, so a hash is a yes-or-no question. */
        const val MTU = MobaUdpProof.MTU

        const val CLIENTS = 2

        /** `runUdpProof`'s lossy link. */
        val LOSSY = NetConditions(latencyTicks = 9, lossChance = 0.05f)

        /**
         * Found by running seeds 1 to 40 with each fix reverted in turn: both seeds disagree with
         * the stall fix reverted, and 9 also disagrees with the overflow fix reverted. A change to
         * the level or the link can move which seeds reach either defect.
         */
        val SEEDS = listOf(2L, 9L)

        const val TICKS = 1300
        const val STOP_ONE_IN = 3
        const val LINK_SEED_STEP = 31L
        const val READER_SEED_STEP = 131L
        const val REPORTED = 8
    }
}
