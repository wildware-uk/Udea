package dev.wildware.udea.net.input

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.bits.readVarInt
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.wire.FrameReader
import dev.wildware.udea.net.wire.MessageType
import dev.wildware.udea.net.wire.PacketHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #108: clients send input, never state, and the server consumes it exactly once per tick.
 *
 * `NetworkClientSystem.kt:57` ran EveryFrame and uploaded full component state for every owned
 * entity; `:75` handled the inbound command packet with a literal `TODO()`. Both halves are
 * covered here — the direction of authority, and the buffer that actually consumes a command.
 */
class InputPathTest {

    private fun command(seq: Int, tick: Long) =
        MoveInput(seq = seq, tick = Tick(tick), moveX = 0.5f, moveY = -0.25f, aim = 1.5f, buttons = 0b101)

    @Test
    fun `a command round trips through the bit stream`() {
        val original = command(9, 42)
        val writer = BitBufferWriter(ByteArray(32))
        original.write(writer)
        val decoded = MoveInput.read(writer.toReader())

        assertEquals(original.seq, decoded.seq)
        assertEquals(original.tick, decoded.tick)
        assertEquals(original.buttons, decoded.buttons)
        // Quantised, so equality is to within the declared step, not exact.
        assertTrue(kotlin.math.abs(original.moveX - decoded.moveX) <= MoveInput.AXIS.maxError)
        assertTrue(kotlin.math.abs(original.aim - decoded.aim) <= MoveInput.AIM.maxError)
    }

    @Test
    fun `every packet carries the last three commands oldest first`() {
        val ring = InputRing()
        repeat(5) { ring.push(command(it, it.toLong())) }
        val writer = BitBufferWriter(ByteArray(64))
        ring.write(writer)

        val reader = writer.toReader()
        assertEquals(3, reader.readVarInt())
        assertEquals(listOf(2, 3, 4), (0 until 3).map { MoveInput.read(reader).seq })
    }

    @Test
    fun `duplicates are dropped and out of order commands cannot rewind the stream`() {
        val buffer = JitterBuffer()
        assertTrue(buffer.offer(command(1, 1)))
        assertTrue(buffer.offer(command(2, 2)))
        assertFalse(buffer.offer(command(2, 2)), "a duplicate sequence was accepted")
        // Still queued, so this is a redundant copy and not a stale command: the difference is
        // exactly what stops a reordered packet's contents being thrown away unsimulated.
        assertFalse(buffer.offer(command(1, 1)), "a queued command was accepted a second time")
        assertEquals(2L, buffer.duplicates)
        assertEquals(0L, buffer.stale)
        assertEquals(2, buffer.depth)

        assertEquals(1, buffer.consume(Tick(1))!!.seq)
        assertFalse(buffer.offer(command(1, 1)), "a command whose tick was already simulated was accepted")
        assertEquals(1L, buffer.stale)
    }

    @Test
    fun `a command that overtakes its predecessor is still simulated in sequence order`() {
        val buffer = JitterBuffer(targetDepth = 1)
        // Arrival order 3, 1, 2 — what a reordering link actually produces. None of them has been
        // simulated, so all three must be accepted, and they must come out 1, 2, 3.
        assertTrue(buffer.offer(command(3, 3)))
        assertTrue(buffer.offer(command(1, 1)))
        assertTrue(buffer.offer(command(2, 2)))
        assertEquals(listOf(1, 2, 3), (1..3).map { buffer.consume(Tick(it.toLong()))!!.seq })
        assertEquals(0L, buffer.stale)
    }

    @Test
    fun `a shuffled and duplicated stream produces the same simulation input as the ordered one`() {
        val commands = (1..40).map { command(it, it.toLong()) }
        val ordered = JitterBuffer()
        val shuffled = JitterBuffer()

        for (c in commands) ordered.offer(c)
        // The same commands, each repeated, in an order no ordered stream would produce.
        val disordered = commands.chunked(3).flatMap { chunk -> (chunk.reversed() + chunk) }
        for (c in disordered) shuffled.offer(c)

        val orderedOut = (1..40).mapNotNull { ordered.consume(Tick(it.toLong())) }
        val shuffledOut = (1..40).mapNotNull { shuffled.consume(Tick(it.toLong())) }
        assertEquals(orderedOut, shuffledOut, "reordering and duplication changed the input stream")
        assertTrue(shuffled.duplicates + shuffled.stale > 0, "the shuffled stream was not actually disordered")
    }

    @Test
    fun `starvation repeats the last command and counts itself instead of freezing`() {
        val buffer = JitterBuffer()
        buffer.offer(command(1, 1))
        buffer.offer(command(2, 2))
        assertEquals(1, buffer.consume(Tick(1))!!.seq)
        assertEquals(2, buffer.consume(Tick(2))!!.seq)

        val starved = buffer.consume(Tick(3))!!
        assertEquals(2, starved.seq, "a starved tick must repeat the last command, not invent one")
        assertEquals(Tick(3), starved.tick, "the repeat must be stamped with the tick it is applied on")
        assertEquals(1L, buffer.starvations)
        assertEquals(2, buffer.lastProcessedInputSeq, "a repeat is not a newly processed command")
    }

    @Test
    fun `nothing is consumed before the first command has ever arrived`() {
        assertEquals(null, JitterBuffer().consume(Tick(1)))
    }

    @Test
    fun `the buffer holds back until the target depth is reached`() {
        val buffer = JitterBuffer(targetDepth = 3)
        buffer.offer(command(1, 1))
        buffer.offer(command(2, 2))
        assertEquals(null, buffer.consume(Tick(1)), "the buffer consumed before it had buffered")
        buffer.offer(command(3, 3))
        assertEquals(1, buffer.consume(Tick(2))!!.seq)
    }

    @Test
    fun `a client flooding the buffer drops the oldest rather than growing without bound`() {
        val buffer = JitterBuffer(capacity = 5)
        repeat(20) { buffer.offer(command(it + 1, (it + 1).toLong())) }
        assertEquals(5, buffer.depth)
        assertEquals(15L, buffer.overflows)
    }

    @Test
    fun `no client to server datagram carries a replicated component field`() {
        lateinit var session: ReplicationSession
        var seq = 0
        session = ReplicationSession(
            seed = 21L,
            mutate = { tick -> if (tick.value == 1L) session.world.spawn(1f, 1f) },
            clientTick = { client, tick ->
                if (tick.value % client.inputInterval == 0L) {
                    client.pushInput(command(seq++, tick.value))
                }
            },
        )
        session.step(40)

        assertTrue(session.clientToServer.size > 10, "the client sent only ${session.clientToServer.size} datagrams")
        var inputFrames = 0
        for (datagram in session.clientToServer) {
            val src = BitBufferReader(datagram, 0, datagram.size)
            val header = PacketHeader.read(src)
            assertFalse(header.hasBaseline, "a client packet claimed to be a delta against a baseline")
            val walker = FrameReader(datagram, 0, datagram.size, src.bitPosition)
            while (true) {
                val frame = walker.next() ?: break
                assertEquals(
                    MessageType.Input,
                    frame.type,
                    "a client-to-server datagram carried a ${frame.type} frame; clients send input only",
                )
                inputFrames++
            }
        }
        assertTrue(inputFrames > 10, "no input frames were sent at all, so the assertion above is vacuous")
        assertTrue(
            session.server.jitterOf(session.clients.single().peer).accepted > 10,
            "the server accepted no input commands",
        )
    }

    @Test
    fun `over a sixty second run at five percent loss more than 99 point 9 percent of commands arrive exactly once`() {
        lateinit var session: ReplicationSession
        var produced = 0
        session = ReplicationSession(
            seed = 4242L,
            conditions = NetConditions(latencyTicks = 4, jitterTicks = 2, lossChance = 0.05f),
            // Deep enough that nothing is dropped for depth: this test measures the *link*, and a
            // buffer overflowing would make it measure the buffer's capacity instead.
            jitterCapacity = 4096,
            mutate = { tick -> if (tick.value == 1L) session.world.spawn(1f, 1f) },
            clientTick = { client, tick ->
                // One command per input tick: 30Hz against the 60Hz sim (spec 3.3). Each command
                // therefore rides three consecutive packets, so losing it needs three consecutive
                // drops rather than one.
                //
                // Input stops 40 ticks before the end so the last commands have their full three
                // chances. Without the drain the final one or two would count as lost when they
                // are merely still in flight, which would be the test measuring its own cut-off.
                if (tick.value <= DRAIN_START && tick.value % client.inputInterval == 0L) {
                    client.pushInput(command(produced++, tick.value))
                }
            },
        )
        session.step(3600)

        val buffer = session.server.jitterOf(session.clients.single().peer)
        val accepted = buffer.accepted
        assertTrue(produced > 1700, "only $produced commands were produced over 3600 ticks")
        assertEquals(0L, buffer.overflows, "the buffer dropped commands for depth, not for loss")
        val delivered = accepted.toDouble() / produced
        assertTrue(
            delivered > 0.999,
            "only ${"%.4f".format(delivered * 100)}% of $produced commands were accepted exactly once " +
                "($accepted accepted, ${buffer.stale} stale, ${buffer.duplicates} duplicates)",
        )
        assertTrue(buffer.duplicates > 1000, "redundancy sent no duplicate copies, so nothing was protected")
        assertTrue(
            session.harness.log.events.count {
                it.kind == dev.wildware.udea.net.transport.PacketEventKind.Dropped
            } > 100,
            "the loss simulation dropped almost nothing, so the delivery figure proves nothing",
        )
    }

    private companion object {

        /** Last tick that produces input, leaving 40 ticks for the final commands to land. */
        const val DRAIN_START: Long = 3560
    }

}
