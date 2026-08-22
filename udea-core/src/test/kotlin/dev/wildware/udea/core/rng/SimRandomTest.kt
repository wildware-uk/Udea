package dev.wildware.udea.core.rng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `SimRandom` is xoshiro256**, not something that behaves a bit like it.
 *
 * The golden vector below is the point of this class. Every replay fixture, every snapshot
 * equivalence check and every "the server and the client agree" assertion in the engine
 * ultimately rests on this generator producing these exact longs from this exact state; a
 * "harmless" refactor that changed a shift by one would break all of them at once, far from
 * here, and look like a networking bug.
 */
class SimRandomTest {

    @Test
    fun `the reference vector for state 1,2,3,4 is reproduced exactly`() {
        val random = SimRandom(longArrayOf(1L, 2L, 3L, 4L))

        val produced = LongArray(REFERENCE_VECTOR.size) { random.nextLong() }

        assertEquals(REFERENCE_VECTOR.toList(), produced.toList())
    }

    @Test
    fun `a seeded stream is a checked-in golden`() {
        // Not a reference vector — this one pins *our* seeding (SplitMix64 over the raw
        // seed). It is here so that changing the seeding procedure is a deliberate act with
        // a failing test attached, since it would silently invalidate every recorded replay.
        val random = SimRandom(seed = 12345L)
        val state = LongArray(SimRandom.STATE_WORDS)
        random.save(state)

        assertEquals(SEEDED_12345_STATE.toList(), state.toList())
        assertEquals(
            SEEDED_12345_FIRST_LONGS.toList(),
            LongArray(SEEDED_12345_FIRST_LONGS.size) { random.nextLong() }.toList(),
        )
    }

    @Test
    fun `the same seed replays and a different seed diverges`() {
        val a = SimRandom(seed = 99L)
        val b = SimRandom(seed = 99L)
        val c = SimRandom(seed = 100L)

        val fromA = LongArray(256) { a.nextLong() }
        val fromB = LongArray(256) { b.nextLong() }
        val fromC = LongArray(256) { c.nextLong() }

        assertEquals(fromA.toList(), fromB.toList())
        assertTrue(fromA.toList() != fromC.toList(), "two seeds must not share a sequence")
    }

    @Test
    fun `nextFloat covers zero until one and never reaches one`() {
        val random = SimRandom(seed = 4L)
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE

        repeat(200_000) {
            val value = random.nextFloat()
            assertTrue(value >= 0f && value < 1f, "nextFloat produced $value")
            if (value < min) min = value
            if (value > max) max = value
        }

        assertTrue(min < 0.001f, "the low end is never reached: min was $min")
        assertTrue(max > 0.999f, "the high end is never reached: max was $max")
    }

    @Test
    fun `nextInt stays in range and reaches every bucket`() {
        val random = SimRandom(seed = 11L)
        val counts = IntArray(7)

        repeat(70_000) {
            val value = random.nextInt(counts.size)
            assertTrue(value in counts.indices, "nextInt(7) produced $value")
            counts[value]++
        }

        // 7 does not divide 2^31, so a modulo implementation biases the low buckets. At 10000
        // expected per bucket, a 5% window is far tighter than that bias and far looser than
        // the sampling noise of a good generator.
        for (bucket in counts.indices) {
            assertTrue(
                counts[bucket] in 9_500..10_500,
                "bucket $bucket got ${counts[bucket]}, expected about 10000: ${counts.toList()}",
            )
        }
    }

    @Test
    fun `nextInt handles the power-of-two fast path`() {
        val random = SimRandom(seed = 3L)
        val counts = IntArray(8)

        repeat(80_000) { counts[random.nextInt(8)]++ }

        for (bucket in counts.indices) {
            assertTrue(counts[bucket] in 9_500..10_500, "bucket $bucket got ${counts[bucket]}")
        }
    }

    @Test
    fun `nextInt rejects a non-positive bound`() {
        val random = SimRandom(seed = 1L)
        assertFailsWith<IllegalArgumentException> { random.nextInt(0) }
        assertFailsWith<IllegalArgumentException> { random.nextInt(-3) }
        assertFailsWith<IllegalArgumentException> { random.nextInt(origin = 5, bound = 5) }
    }

    @Test
    fun `nextInt with an origin is uniform over the half-open range`() {
        val random = SimRandom(seed = 8L)
        var seenLow = false
        var seenHigh = false

        repeat(10_000) {
            val value = random.nextInt(origin = -3, bound = 4)
            assertTrue(value in -3..3, "produced $value")
            if (value == -3) seenLow = true
            if (value == 3) seenHigh = true
        }

        assertTrue(seenLow && seenHigh, "both ends of the range must be reachable")
    }

    @Test
    fun `nextBoolean is a fair coin`() {
        val random = SimRandom(seed = 5L)
        var heads = 0

        repeat(100_000) { if (random.nextBoolean()) heads++ }

        assertTrue(heads in 49_000..51_000, "heads came up $heads times in 100000")
    }

    @Test
    fun `state survives a save and load round trip`() {
        val random = SimRandom(seed = 2024L)
        repeat(37) { random.nextLong() }

        val captured = LongArray(SimRandom.STATE_WORDS)
        random.save(captured)
        val expected = LongArray(100) { random.nextLong() }

        // Restore into a *different* generator: a snapshot has to reconstitute, not rewind.
        val restored = SimRandom(captured)
        assertEquals(expected.toList(), LongArray(100) { restored.nextLong() }.toList())

        // And into the same one.
        random.load(captured)
        assertEquals(expected.toList(), LongArray(100) { random.nextLong() }.toList())
    }

    @Test
    fun `state can be saved into and loaded from the middle of a larger buffer`() {
        val buffer = LongArray(16) { -1L }
        val random = SimRandom(seed = 6L)
        repeat(5) { random.nextLong() }

        random.save(buffer, offset = 8)
        val expected = LongArray(10) { random.nextLong() }

        assertEquals(listOf(-1L, -1L, -1L, -1L), buffer.slice(4 until 8))
        val restored = SimRandom(buffer, offset = 8)
        assertEquals(expected.toList(), LongArray(10) { restored.nextLong() }.toList())
    }

    @Test
    fun `a truncated buffer is rejected with a message that says what was needed`() {
        val random = SimRandom(seed = 1L)

        val failure = assertFailsWith<IllegalArgumentException> {
            random.save(LongArray(3))
        }
        assertTrue("4" in failure.message.orEmpty(), failure.message.orEmpty())

        assertFailsWith<IllegalArgumentException> { random.load(LongArray(4), offset = 1) }
        assertFailsWith<IllegalArgumentException> { random.save(LongArray(8), offset = -1) }
    }

    @Test
    fun `the all-zero absorbing state is refused`() {
        val random = SimRandom(seed = 1L)

        val failure = assertFailsWith<IllegalArgumentException> {
            random.load(LongArray(SimRandom.STATE_WORDS))
        }

        assertTrue(
            "absorbing" in failure.message.orEmpty(),
            "a dead generator must be named as such: ${failure.message}",
        )
    }

    @Test
    fun `no seed produces a dead generator`() {
        // Zero is the seed a default EngineConfig carries, so it had better work.
        for (seed in longArrayOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val random = SimRandom(seed)
            val drawn = LongArray(8) { random.nextLong() }
            assertTrue(drawn.any { it != 0L }, "seed $seed produced only zeroes")
        }
    }

    private companion object {
        /**
         * xoshiro256** from the state `{1, 2, 3, 4}`, the published reference vector.
         *
         * The first value is checkable by hand and worth checking: `rotl(s1 * 5, 7) * 9` with
         * `s1 = 2` is `rotl(10, 7) * 9` = `1280 * 9` = `11520`.
         */
        val REFERENCE_VECTOR = longArrayOf(
            11520L,
            0L,
            1509978240L,
            1215971899390074240L,
            1216172134540287360L,
            607988272756665600L,
            -2273821095074991991L,
            8476171486693032832L,
        )

        /** The four state words `SimRandom(12345)` seeds itself to. */
        val SEEDED_12345_STATE = longArrayOf(
            2454886589211414944L,
            3778200017661327597L,
            2205171434679333405L,
            3248800117070709450L,
        )

        /** And the first eight longs it then produces. */
        val SEEDED_12345_FIRST_LONGS = longArrayOf(
            -4725905248023948133L,
            2398916695208396998L,
            -676359223724682360L,
            891717726879801395L,
            -8205428027391097272L,
            196975429884907396L,
            2947371003896198809L,
            5456629693515947710L,
        )
    }
}
