package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three bounded resources a hostile peer would otherwise grow: reply size, handshake rate,
 * and reassembly memory.
 *
 * Each is tested here on its own, away from the socket, so that a failure names the rule rather
 * than "the server did something odd". `UdpHostileTest` then proves the same rules hold when the
 * bytes are real.
 */
class UdpGuardTest {

    // --- anti-amplification -----------------------------------------------------------------

    @Test
    fun `a reply no larger than what arrived is permitted`() {
        val guard = AmplificationGuard()

        assertTrue(guard.permits(replyBytes = 25, receivedBytes = 1200))
        assertTrue(guard.permits(replyBytes = 1200, receivedBytes = 1200))
        assertEquals(0L, guard.blocked)
    }

    @Test
    fun `a reply larger than what arrived is withheld and counted`() {
        val guard = AmplificationGuard()

        assertFalse(guard.permits(replyBytes = 1201, receivedBytes = 1200))
        assertFalse(guard.permits(replyBytes = 25, receivedBytes = 20))

        assertEquals(2L, guard.blocked)
    }

    // --- rate limiting ----------------------------------------------------------------------

    @Test
    fun `one address gets its burst and then nothing until it refills`() {
        val limiter = HandshakeRateLimiter()
        val peer = address(1)
        val now = Tick(100)

        val allowedInBurst = (0 until HandshakeRateLimiter.DEFAULT_BURST.toInt() + 4).count {
            limiter.allow(peer, now)
        }

        assertEquals(HandshakeRateLimiter.DEFAULT_BURST.toInt(), allowedInBurst)
        assertEquals(4L, limiter.addressLimited)
        assertFalse(limiter.allow(peer, now + 1L), "the bucket refilled inside one tick")

        val refillTicks = (1f / HandshakeRateLimiter.DEFAULT_REFILL_PER_TICK).toLong()
        assertTrue(limiter.allow(peer, now + refillTicks + 1L), "the bucket never refilled")
    }

    @Test
    fun `a different source port does not buy a fresh bucket`() {
        val limiter = HandshakeRateLimiter()
        val now = Tick(1)
        repeat(HandshakeRateLimiter.DEFAULT_BURST.toInt()) {
            assertTrue(limiter.allow(InetSocketAddress(InetAddress.getByAddress(HOST), 5000 + it), now))
        }

        val fromAnotherPort = limiter.allow(InetSocketAddress(InetAddress.getByAddress(HOST), 6000), now)

        assertFalse(fromAnotherPort, "walking the source port defeated the limiter")
    }

    @Test
    fun `the global budget bounds a tick however many addresses are attacking`() {
        val limiter = HandshakeRateLimiter()
        val now = Tick(7)

        val allowed = (0 until HandshakeRateLimiter.DEFAULT_GLOBAL_PER_TICK * 4).count {
            limiter.allow(address(it), now)
        }

        assertEquals(HandshakeRateLimiter.DEFAULT_GLOBAL_PER_TICK, allowed)
        assertTrue(limiter.globalLimited > 0L)
        assertTrue(limiter.allow(address(999), now + 1L), "the next tick got a fresh budget")
    }

    // --- fragment reassembly ----------------------------------------------------------------

    @Test
    fun `three fragments rebuild the message they were cut from`() {
        val reassembler = FragmentReassembler(fragmentBytes = FRAGMENT)
        val message = ByteArray(2 * FRAGMENT + 9) { (it % 251).toByte() }

        assertNull(reassembler.accept(1, 0, 3, message, 0, FRAGMENT, Tick(1)))
        assertNull(reassembler.accept(1, 2, 3, message, 2 * FRAGMENT, 9, Tick(1)))
        val done = assertNotNull(reassembler.accept(1, 1, 3, message, FRAGMENT, FRAGMENT, Tick(1)))

        assertEquals(message.size, done.totalBytes)
        assertContentEquals(message, done.payload.copyOfRange(0, done.totalBytes))
    }

    @Test
    fun `a short middle fragment is refused and takes its assembly with it`() {
        val reassembler = FragmentReassembler(fragmentBytes = FRAGMENT)
        val message = ByteArray(2 * FRAGMENT)

        reassembler.accept(2, 0, 2, message, 0, FRAGMENT, Tick(1))
        val result = reassembler.accept(2, 0, 2, message, 0, FRAGMENT - 1, Tick(1))

        assertNull(result)
        assertEquals(1L, reassembler.refused)
        // The abandoned slot is free again rather than pinned until its deadline.
        reassembler.accept(3, 1, 2, message, 0, 4, Tick(2))
        assertNotNull(
            reassembler.accept(3, 0, 2, message, 0, FRAGMENT, Tick(2)),
            "the refused fragment left its slot pinned",
        )
    }

    @Test
    fun `a fragment count beyond the bound is refused outright`() {
        val reassembler = FragmentReassembler(fragmentBytes = FRAGMENT, maxFragments = 4)

        assertNull(reassembler.accept(4, 0, 5, ByteArray(FRAGMENT), 0, FRAGMENT, Tick(1)))
        assertNull(reassembler.accept(4, 0, 1, ByteArray(FRAGMENT), 0, FRAGMENT, Tick(1)))

        assertEquals(2L, reassembler.refused)
    }

    @Test
    fun `a part built message is abandoned once its deadline passes`() {
        val reassembler = FragmentReassembler(fragmentBytes = FRAGMENT, timeoutTicks = 10L)
        val message = ByteArray(2 * FRAGMENT)
        reassembler.accept(5, 0, 2, message, 0, FRAGMENT, Tick(1))

        reassembler.expire(Tick(11))
        assertEquals(0L, reassembler.timedOut, "the deadline is inclusive, so tick 11 is still in time")
        reassembler.expire(Tick(12))

        assertEquals(1L, reassembler.timedOut)
        // The late second half now finds nothing to attach to, so it opens a fresh assembly and
        // does not complete a message the receiver waited a whole timeout for.
        assertNull(reassembler.accept(5, 1, 2, message, FRAGMENT, FRAGMENT, Tick(13)))
    }

    @Test
    fun `more concurrent messages than slots evicts rather than allocating`() {
        val reassembler = FragmentReassembler(fragmentBytes = FRAGMENT, maxAssemblies = 2)
        val message = ByteArray(2 * FRAGMENT)

        for (id in 0 until 50) reassembler.accept(id, 0, 2, message, 0, FRAGMENT, Tick(id.toLong()))

        assertEquals(48L, reassembler.evicted)
        assertEquals(2, reassembler.allocatedAssemblies, "the attacker made the receiver allocate")
    }

    private fun address(index: Int): InetSocketAddress =
        InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, (index shr 8).toByte(), index.toByte(), 1)), 4000)

    private companion object {

        const val FRAGMENT: Int = 64

        val HOST: ByteArray = byteArrayOf(10, 1, 2, 3)
    }
}
