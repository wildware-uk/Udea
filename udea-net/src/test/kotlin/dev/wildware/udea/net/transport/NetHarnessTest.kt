package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A test endpoint that produces traffic whose content is a pure function of the tick.
 *
 * Deliberately not random: the property under test is that the *transport* is deterministic, and
 * a payload drawn from its own generator would make an identical log prove only that two
 * generators agreed.
 */
private class ChattyEndpoint(
    override val peer: PeerId,
    private val transport: Transport,
    private val targets: List<PeerId>,
    private val payloadBytes: Int = 24,
) : NetEndpoint {

    private val buffer = ByteArray(payloadBytes)

    var received: Int = 0
        private set

    var receivedBytes: Long = 0L
        private set

    override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
        received++
        receivedBytes += length.toLong()
    }

    override fun onTick(tick: Tick) {
        for (index in buffer.indices) {
            buffer[index] = (tick.value + index + peer.raw).toByte()
        }
        for (target in targets) transport.send(target, buffer, 0, payloadBytes)
    }
}

/**
 * `net.spawn_session(clients = 4)` driven for 600 ticks, twice, with no sockets and no threads.
 */
class NetHarnessTest {

    private fun runSession(seed: Long, conditions: NetConditions): NetHarness {
        val harness = NetHarness(clients = 4, seed = seed, initialConditions = conditions)
        val clients = harness.clientPeers()
        harness.register(ChattyEndpoint(harness.server, harness.transport(harness.server), clients))
        for (client in clients) {
            harness.register(
                ChattyEndpoint(client, harness.transport(client), listOf(harness.server)),
            )
        }
        harness.step(600)
        return harness
    }

    @Test
    fun `a four client six hundred tick session runs on one thread with no sleeps`() {
        val startedOn = Thread.currentThread()
        val startedThreads = Thread.activeCount()
        val began = System.nanoTime()
        val harness = runSession(seed = 7L, conditions = NetConditions.TRELLO_8)
        val elapsedMillis = (System.nanoTime() - began) / 1_000_000

        assertEquals(Tick(600), harness.clock.tick)
        assertTrue(harness.log.size > 5_000, "a 600-tick 4-client session logged only ${harness.log.size} events")
        assertTrue(elapsedMillis < 2_000, "600 ticks took ${elapsedMillis}ms, over the two second bound")
        assertEquals(startedOn, Thread.currentThread(), "the harness moved work to another thread")
        assertTrue(
            Thread.activeCount() <= startedThreads,
            "the harness left ${Thread.activeCount() - startedThreads} extra live thread(s)",
        )
    }

    @Test
    fun `the same seed produces a byte identical packet log`() {
        val first = runSession(seed = 4242L, conditions = NetConditions.TRELLO_8)
        val second = runSession(seed = 4242L, conditions = NetConditions.TRELLO_8)
        assertEquals(first.log.render(), second.log.render())
    }

    @Test
    fun `a different seed produces a different packet log`() {
        // Without this the determinism assertion above would also pass for a simulation whose
        // probabilities never fire — an "identical log" that is identical because nothing happens.
        val first = runSession(seed = 1L, conditions = NetConditions.TRELLO_8)
        val second = runSession(seed = 2L, conditions = NetConditions.TRELLO_8)
        assertNotEquals(first.log.render(), second.log.render())
    }

    @Test
    fun `transport stats total exactly what the packet log recorded`() {
        val harness = runSession(seed = 99L, conditions = NetConditions.TRELLO_8)
        val peers = listOf(harness.server) + harness.clientPeers()

        for (peer in peers) {
            val transport = harness.transport(peer)
            val loggedSent = harness.log.events.count { it.kind == PacketEventKind.Sent && it.from == peer }
            val loggedDropped = harness.log.events.count { it.kind == PacketEventKind.Dropped && it.from == peer }
            val loggedDelivered = harness.log.events.count { it.kind == PacketEventKind.Delivered && it.to == peer }

            var sent = 0L
            var dropped = 0L
            var received = 0L
            for (other in peers) {
                val stats = transport.stats(other)
                sent += stats.packetsSent
                dropped += stats.packetsDropped
                received += stats.packetsReceived
            }
            assertEquals(loggedSent.toLong(), sent, "$peer sent")
            assertEquals(loggedDropped.toLong(), dropped, "$peer dropped")
            assertEquals(loggedDelivered.toLong(), received, "$peer received")
        }
    }

    @Test
    fun `loss drops packets and a perfect link drops none`() {
        val lossy = runSession(seed = 11L, conditions = NetConditions(lossChance = 0.05f))
        val perfect = runSession(seed = 11L, conditions = NetConditions.PERFECT)

        val dropped = lossy.log.events.count { it.kind == PacketEventKind.Dropped }
        assertTrue(dropped > 0, "5% loss over 3000 sends dropped nothing at all")
        assertEquals(0, perfect.log.events.count { it.kind == PacketEventKind.Dropped })
    }

    @Test
    fun `latency delays delivery by exactly the configured number of ticks`() {
        val harness = NetHarness(clients = 1, initialConditions = NetConditions(latencyTicks = 9))
        val client = PeerId.client(1)
        val server = harness.register(
            ChattyEndpoint(harness.server, harness.transport(harness.server), listOf(client)),
        )
        val receiver = harness.register(
            ChattyEndpoint(client, harness.transport(client), emptyList()),
        )

        // The first datagram is sent during onTick(1) with a deadline of tick 10. The release
        // pass runs at the top of each tick, before the send pass, so tick 9 cannot deliver it
        // and tick 10 must.
        harness.step(9)
        assertEquals(0, receiver.received, "delivery happened before the configured latency elapsed")
        harness.step(1)
        assertEquals(1, receiver.received, "the first datagram did not arrive on tick 10")
        assertEquals(0, server.received)
    }

    @Test
    fun `the bandwidth cap defers datagrams instead of dropping them`() {
        val harness = NetHarness(
            clients = 1,
            initialConditions = NetConditions(bytesPerTick = 24),
        )
        val client = PeerId.client(1)
        // 48 bytes offered per tick against a 24-byte cap: half must queue, none may vanish.
        harness.register(
            object : NetEndpoint {
                override val peer: PeerId = harness.server
                private val payload = ByteArray(24)
                override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) = Unit
                override fun onTick(tick: Tick) {
                    harness.transport(peer).send(client, payload, 0, payload.size)
                    harness.transport(peer).send(client, payload, 0, payload.size)
                }
            },
        )
        val receiver = harness.register(ChattyEndpoint(client, harness.transport(client), emptyList()))

        harness.step(20)
        assertEquals(0, harness.log.events.count { it.kind == PacketEventKind.Dropped })
        assertTrue(
            receiver.received in 18..20,
            "a 24 byte/tick cap over 20 ticks delivered ${receiver.received} datagrams",
        )
        assertTrue(harness.transport(harness.server).inFlight > 0, "nothing was left queued behind the cap")
    }

    /**
     * Steady-state sends allocate no datagram buffers.
     *
     * Fixed latency and no jitter, deliberately: with jitter the number of datagrams in flight is
     * a random variable whose maximum keeps creeping up over a long run, so "the pool never grows
     * again" would be a claim that is not true rather than a claim that is hard to test. With a
     * fixed delay the in-flight count is a constant, the pool converges, and zero growth over 600
     * further ticks is an exact assertion.
     */
    @Test
    fun `the datagram pool stops allocating once the session is warm`() {
        val harness = runSession(seed = 5L, conditions = NetConditions(latencyTicks = 9))
        val warm = harness.allocatedDatagrams()
        harness.step(600)
        assertEquals(warm, harness.allocatedDatagrams(), "the datagram pool grew during a warm session")
    }
}
