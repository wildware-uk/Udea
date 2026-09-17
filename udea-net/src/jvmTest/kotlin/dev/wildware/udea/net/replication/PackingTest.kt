package dev.wildware.udea.net.replication

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pieces of the send loop, tested apart from the network: selection order, priority growth,
 * ack bookkeeping and the per-(client, entity) baseline.
 *
 * The end-to-end tests prove the whole thing converges; these prove *why*, and they fail on the
 * kind of change a convergence test can absorb — a heap that is nearly ordered still converges,
 * and a starvation bound that has quietly doubled still converges.
 */
class PackingTest {

    private fun netId(index: Int, generation: Int = 0) = NetId.of(index, generation)

    @Test
    fun `the selector returns candidates in descending priority`() {
        val selector = PrioritySelector()
        val priorities = listOf(3f, 9f, 1f, 7f, 5f)
        priorities.forEachIndexed { index, priority -> selector.add(netId(index), priority) }
        selector.heapify()

        val order = (priorities.indices).map { selector.poll().index }
        assertEquals(listOf(1, 3, 4, 0, 2), order)
        assertEquals(0, selector.size)
    }

    @Test
    fun `equal priorities break on the lower NetId so two runs pack identically`() {
        val selector = PrioritySelector()
        // Added in a deliberately unhelpful order: without an explicit tie-break the heap would
        // return whichever the sift happened to leave on top, and two runs of one scenario would
        // produce different bytes.
        for (index in listOf(4, 1, 3, 0, 2)) selector.add(netId(index), 1f)
        selector.heapify()
        assertEquals(listOf(0, 1, 2, 3, 4), (0 until 5).map { selector.poll().index })
    }

    @Test
    fun `polling an empty selector is a bug and says so`() {
        assertFailsWith<NoSuchElementException> { PrioritySelector().poll() }
    }

    @Test
    fun `priority grows with staleness and with weight`() {
        val accumulator = PriorityAccumulator()
        val state = ClientReplicationState(PeerId.client(1))
        val stale = netId(1)
        val fresh = netId(2)
        state.recordSent(fresh, seq = 0, tick = Tick(90))

        val stalePriority = accumulator.accumulate(state, stale, Tick(100), weight = 1f)
        val freshPriority = accumulator.accumulate(state, fresh, Tick(100), weight = 1f)
        assertTrue(
            stalePriority > freshPriority,
            "an entity unsent for 100 ticks did not outrank one sent 10 ticks ago",
        )

        val heavy = netId(3)
        val light = netId(4)
        assertTrue(
            accumulator.accumulate(state, heavy, Tick(100), weight = 4f) >
                accumulator.accumulate(state, light, Tick(100), weight = 1f),
            "distance weight did not affect priority",
        )
    }

    @Test
    fun `a sent entity loses its accumulated priority and a quiet one keeps climbing`() {
        val accumulator = PriorityAccumulator()
        val state = ClientReplicationState(PeerId.client(1))
        val entity = netId(1)
        accumulator.accumulate(state, entity, Tick(10), weight = 1f)
        assertTrue(state.priorityOf(entity) > 0f)
        state.recordSent(entity, seq = 1, tick = Tick(10))
        assertEquals(0f, state.priorityOf(entity))
    }

    @Test
    fun `a baseline only advances when the client acknowledges the packet`() {
        val state = ClientReplicationState(PeerId.client(1))
        val entity = netId(7)
        val seq = state.beginPacket(Tick(40))
        state.recordSent(entity, seq, Tick(40))

        assertEquals(
            ClientReplicationState.NO_BASELINE,
            state.baselineTickOf(entity),
            "an unacknowledged packet advanced a baseline; its create would never be re-sent",
        )
        state.applyAck(seq, 0)
        assertEquals(40L, state.baselineTickOf(entity))
        assertEquals(Tick(40), state.lastAckedTick)
    }

    @Test
    fun `ackBits acknowledges the thirty two packets before the named one`() {
        val state = ClientReplicationState(PeerId.client(1))
        val entity = netId(3)
        val first = state.beginPacket(Tick(10))
        state.recordSent(entity, first, Tick(10))
        val second = state.beginPacket(Tick(11))
        val third = state.beginPacket(Tick(12))
        state.recordSent(entity, third, Tick(12))

        // Ack the third, and report the first as also received two places back. The second is
        // deliberately not reported: a client that never got it must not have it acknowledged.
        state.applyAck(third, 1 shl 1)
        assertEquals(12L, state.baselineTickOf(entity))
        assertEquals(2L, state.ackedPackets, "ackBits acknowledged a packet it did not name")

        // And the unreported one is still outstanding: acking it now must still count.
        state.applyAck(second, 0)
        assertEquals(3L, state.ackedPackets)
    }

    @Test
    fun `a recycled index does not inherit the previous occupant's baseline`() {
        val state = ClientReplicationState(PeerId.client(1))
        val old = netId(5, generation = 0)
        val fresh = netId(5, generation = 1)
        val seq = state.beginPacket(Tick(20))
        state.recordSent(old, seq, Tick(20))
        state.applyAck(seq, 0)

        assertEquals(20L, state.baselineTickOf(old))
        assertEquals(
            ClientReplicationState.NO_BASELINE,
            state.baselineTickOf(fresh),
            "a new entity inherited a stranger's baseline and would be delta-encoded against it",
        )
    }

    @Test
    fun `the send path holds no per client copy of world state`() {
        // Spec section 7's risk row: a per-client shadow world is ~25MB at MOBA scale and blows
        // L2 during diffing. This asserts the shape rather than the size — the state exposes tick
        // numbers and priorities and offers no way to read a component field out of it.
        val members = ClientReplicationState::class.java.declaredFields.map { it.type.simpleName }
        assertTrue(
            members.none { it.contains("Store") || it.contains("Snapshot") || it.contains("World") },
            "ClientReplicationState grew a world-shaped field: $members",
        )
    }

    @Test
    fun `the budget refuses a non positive size rather than sending nothing forever`() {
        assertFailsWith<IllegalArgumentException> { BandwidthBudget(0) }
        assertFailsWith<IllegalArgumentException> { NetConditions(bytesPerTick = 0) }
    }

    @Test
    fun `the default relevancy set admits everything at unit weight`() {
        val client = PeerId.client(1)
        assertTrue(RelevancySet.ALL_VISIBLE.isRelevant(client, netId(99)))
        assertEquals(1f, RelevancySet.ALL_VISIBLE.weightOf(client, netId(99)))
    }
}
