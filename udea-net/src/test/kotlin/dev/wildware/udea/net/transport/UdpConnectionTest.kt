package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sequencing, acknowledgement and replay rules, with no socket in the way.
 *
 * The socket-level wraparound test in `UdpTransportTest` proves the property end to end but can
 * only assert on it statistically, because real UDP is allowed to drop. This one asserts on it
 * exactly: two hundred thousand sequences, every single one accepted, and a named counter for
 * every kind of refusal. Wraparound bugs are invisible for the first eighteen minutes of a
 * session and then permanent, so the exact version is the one that has to exist.
 */
class UdpConnectionTest {

    @Test
    fun `two hundred thousand consecutive sequences are all accepted across three wraps`() {
        val connection = connection()
        var accepted = 0

        for (index in 0 until TOTAL) {
            if (connection.onReceived(index and SEQ_MASK, Tick(index.toLong()))) accepted++
        }

        assertEquals(TOTAL, accepted, "acceptance stopped somewhere in the sequence space")
        assertEquals(0L, connection.replayDropped, "a wrapped sequence was read as a replay")
        assertEquals(0L, connection.staleDropped, "a wrapped sequence was read as stale")
        assertEquals((TOTAL - 1) and SEQ_MASK, connection.remoteSeq)
    }

    @Test
    fun `the sequence right after a wrap is newer than the one right before it`() {
        val connection = connection()

        assertTrue(connection.onReceived(SEQ_MASK, Tick(1)), "65535 should be the first sequence")
        assertTrue(connection.onReceived(0, Tick(2)), "0 must be newer than 65535, not older")

        assertEquals(0, connection.remoteSeq)
        assertEquals(0L, connection.staleDropped)
    }

    @Test
    fun `a sequence that arrives late but inside the window is accepted exactly once`() {
        val connection = connection()
        connection.onReceived(10, Tick(1))
        connection.onReceived(12, Tick(2))

        assertTrue(connection.onReceived(11, Tick(3)), "a reordered datagram was refused")
        assertFalse(connection.onReceived(11, Tick(4)), "a duplicate was accepted")
        assertEquals(1L, connection.replayDropped)
        assertEquals(12, connection.remoteSeq, "a late datagram must not move the newest sequence")
    }

    @Test
    fun `the newest sequence arriving twice is refused`() {
        val connection = connection()
        connection.onReceived(5, Tick(1))

        assertFalse(connection.onReceived(5, Tick(2)))
        assertEquals(1L, connection.replayDropped)
    }

    @Test
    fun `a sequence older than the window can vouch for is refused as stale`() {
        val connection = connection()
        connection.onReceived(0, Tick(1))
        connection.onReceived(UdpConnection.ACK_BITS + 5, Tick(2))

        assertFalse(connection.onReceived(1, Tick(3)), "a sequence past the window was accepted")
        assertEquals(1L, connection.staleDropped)
        assertEquals(0L, connection.replayDropped)
    }

    @Test
    fun `the acknowledgement bitfield names the last thirty two sequences`() {
        val connection = connection()
        connection.onReceived(1, Tick(1))
        connection.onReceived(2, Tick(2))
        connection.onReceived(4, Tick(3))

        assertEquals(4, connection.remoteSeq)
        // Bit 0 is sequence 3, which never arrived; bit 1 is 2 and bit 2 is 1, which did.
        assertEquals(0b110, connection.remoteAckBits)
    }

    @Test
    fun `a round trip is measured from the acknowledgement of the packet that carried it`() {
        val connection = connection()
        val seq = connection.beginSend(Tick(10))

        connection.onAck(seq, ackBits = 0, now = Tick(19))

        assertEquals(9f, connection.smoothedRttTicks, "the first sample should be taken as the estimate")
        assertTrue(connection.rtoTicks >= 9L, "the timeout should cover the measured trip")
    }

    @Test
    fun `an acknowledgement repeated in ackBits does not resample the same packet`() {
        val connection = connection()
        val first = connection.beginSend(Tick(10))
        connection.onAck(first, ackBits = 0, now = Tick(30))
        val afterFirst = connection.smoothedRttTicks

        // The peer keeps restating the same acknowledgement, tick after tick, as it must.
        repeat(20) { connection.onAck(first, ackBits = 0, now = Tick(31L + it)) }

        assertEquals(afterFirst, connection.smoothedRttTicks, "a restated ack dragged the estimate")
    }

    @Test
    fun `the retransmit timeout never falls below its floor or rises above its ceiling`() {
        val fast = connection(minRto = 6L, maxRto = 240L)
        val seq = fast.beginSend(Tick(0))
        fast.onAck(seq, ackBits = 0, now = Tick(1))
        assertEquals(6L, fast.rtoTicks, "a one-tick trip should still wait the floor")

        val slow = connection(minRto = 6L, maxRto = 20L)
        val slowSeq = slow.beginSend(Tick(0))
        slow.onAck(slowSeq, ackBits = 0, now = Tick(500))
        assertEquals(20L, slow.rtoTicks, "a very slow trip should still be capped")
    }

    private fun connection(minRto: Long = 6L, maxRto: Long = 240L): UdpConnection = UdpConnection(
        peer = PeerId.client(1),
        address = loopback(30_000),
        salt = 0xC0FFEEL,
        stats = TransportStats(PeerId.client(1)),
        fragmentBytes = 1178,
        createdAt = Tick.ZERO,
        minRtoTicks = minRto,
        maxRtoTicks = maxRto,
    )

    private companion object {

        /** The Phase 4 exit figure: three and a bit wraps of a 16-bit space. */
        const val TOTAL: Int = 200_000

        const val SEQ_MASK: Int = 0xFFFF
    }
}
