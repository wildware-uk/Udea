package dev.wildware.udea.core.rng

import dev.wildware.udea.core.RngStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The snapshot writes this array directly, so its shape is part of the snapshot format.
 *
 * A wrong-sized array is refused rather than padded or truncated. The failure it prevents is
 * the worst kind: a snapshot captured before a stream was added would restore four streams
 * and silently leave the fifth wherever it happened to be, so restore-and-rerun would diverge
 * from the capture — the one property the whole determinism gate rests on.
 */
class RngStateLayoutTest {

    @Test
    fun `the state is four longs per stream`() {
        val service = DefaultRngService(1L)
        val expected = RngStream.entries.size * SimRandom.STATE_WORDS

        assertEquals(expected, service.saveState().size)
        assertEquals(expected, service.stateWords)
    }

    @Test
    fun `the layout is stream-ordinal-major`() {
        val service = DefaultRngService(77L)
        repeat(9) { RngStream.entries.forEach { service.stream(it).nextLong() } }

        val flat = service.saveState()

        for (stream in RngStream.entries) {
            val own = LongArray(SimRandom.STATE_WORDS)
            service.stream(stream).save(own)
            val slice = flat.slice(
                stream.ordinal * SimRandom.STATE_WORDS until
                    (stream.ordinal + 1) * SimRandom.STATE_WORDS,
            )
            assertEquals(own.toList(), slice, "$stream is not at ordinal ${stream.ordinal}")
        }
    }

    @Test
    fun `a wrong-sized array is rejected with a message that names the expected size`() {
        val service = DefaultRngService(1L)
        val expected = service.stateWords

        for (size in intArrayOf(0, expected - 1, expected - 4, expected + 1)) {
            val failure = assertFailsWith<IllegalArgumentException>("size $size was accepted") {
                service.restore(LongArray(size) { 1L })
            }
            val message = failure.message.orEmpty()
            assertTrue("$expected" in message, "expected size missing from: $message")
            assertTrue("$size" in message, "actual size missing from: $message")
        }
    }

    @Test
    fun `a full-sized array round trips`() {
        val service = DefaultRngService(1L)
        repeat(20) { service.stream(RngStream.Wave).nextLong() }
        val captured = service.saveState()

        service.restore(captured)

        assertEquals(captured.toList(), service.saveState().toList())
    }

    @Test
    fun `state can be written into and read out of a larger snapshot buffer`() {
        val service = DefaultRngService(5L)
        repeat(11) { service.stream(RngStream.Combat).nextLong() }
        val expected = service.saveState()

        val snapshot = LongArray(service.stateWords + 16) { -7L }
        service.saveInto(snapshot, offset = 8)

        assertEquals(expected.toList(), snapshot.slice(8 until 8 + service.stateWords))
        assertEquals(listOf(-7L, -7L), snapshot.slice(6 until 8))

        val restored = DefaultRngService(999L)
        restored.restoreFrom(snapshot, offset = 8)
        assertEquals(expected.toList(), restored.saveState().toList())
    }

    @Test
    fun `an offset with no room is rejected`() {
        val service = DefaultRngService(1L)
        val tooSmall = LongArray(service.stateWords)

        assertFailsWith<IllegalArgumentException> { service.saveInto(tooSmall, offset = 1) }
        assertFailsWith<IllegalArgumentException> { service.restoreFrom(tooSmall, offset = 1) }
        assertFailsWith<IllegalArgumentException> { service.saveInto(tooSmall, offset = -1) }
    }

    @Test
    fun `a stream ordinal is never negative`() {
        assertFailsWith<IllegalArgumentException> { DefaultRngService.streamSeed(0L, -1) }
    }
}
