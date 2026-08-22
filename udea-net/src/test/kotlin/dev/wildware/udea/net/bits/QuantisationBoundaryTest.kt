package dev.wildware.udea.net.bits

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The edges of every quantisation: the declared bounds themselves, values outside them,
 * the infinities, and NaN.
 *
 * The interesting half of a quantiser is what it does with a value it was not promised.
 */
class QuantisationBoundaryTest {

    private val buffer = ByteArray(32)
    private val writer = BitBufferWriter(buffer)
    private val reader = BitBufferReader(buffer)

    private fun roundTripFixed(value: Float, min: Float, max: Float, bits: Int): Float {
        writer.reset()
        writer.writeFixed(value, min, max, bits)
        reader.reset()
        return reader.readFixed(min, max, bits)
    }

    /**
     * `Q`'s round-trip contract, stated as code: `decoded` is within `maxError` of `expected`
     * once `expected` has been reduced into the kind's own domain.
     *
     * The reduction is the half a caller cannot guess, so it is spelled out per kind here.
     * An exhaustive `when` rather than an `if (q === Q.Angle16)`: a kind added later fails to
     * compile at this line instead of being silently measured as a bounded float, which is
     * the reason [Q] is sealed at all.
     */
    private fun roundTripError(q: Q, expected: Float, decoded: Float): Float = when (q) {
        // Wrapping: angleDistance performs the reduction as part of measuring, because a
        // difference of one turn is a difference of nothing.
        is Q.Angle16 -> angleDistance(decoded, expected)
        // Clamping: the reduction is a coerce, and it is what makes an out-of-range write
        // land on a bound rather than exceed the bound's own error.
        is Q.Norm8 -> abs(decoded - expected.coerceIn(0f, 1f))
        is Q.Pos -> abs(decoded - expected.coerceIn(Q.Pos.MIN, Q.Pos.MAX))
        is Q.Fixed -> abs(decoded - expected.coerceIn(q.min, q.max))
        // No reduction and no error.
        is Q.Exact -> abs(decoded - expected)
    }

    @Test
    fun `both declared bounds are exactly representable`() {
        for (bits in 1..32) {
            assertEquals(-100f, roundTripFixed(-100f, -100f, 100f, bits), "min at $bits bits")
            assertEquals(100f, roundTripFixed(100f, -100f, 100f, bits), "max at $bits bits")
        }
    }

    @Test
    fun `values outside the range clamp to the bound they passed`() {
        assertEquals(-100f, roundTripFixed(-100.001f, -100f, 100f, 12))
        assertEquals(-100f, roundTripFixed(-1e30f, -100f, 100f, 12))
        assertEquals(-100f, roundTripFixed(Float.NEGATIVE_INFINITY, -100f, 100f, 12))
        assertEquals(100f, roundTripFixed(100.001f, -100f, 100f, 12))
        assertEquals(100f, roundTripFixed(1e30f, -100f, 100f, 12))
        assertEquals(100f, roundTripFixed(Float.POSITIVE_INFINITY, -100f, 100f, 12))
    }

    @Test
    fun `just inside a bound does not clamp`() {
        // One step in from the top must decode to one step in from the top, not to the top:
        // a clamp that swallowed the last step would hide a whole step of range.
        val bits = 12
        val step = 200.0 / ((1L shl bits) - 1L)
        val justInside = (100.0 - step).toFloat()
        val decoded = roundTripFixed(justInside, -100f, 100f, bits)
        assertNotEquals(100f, decoded, "a value a full step below max must not clamp to max")
        assertTrue(abs(decoded - justInside) <= step / 2.0 + Math.ulp(decoded))
    }

    @Test
    fun `a bounded quantiser rejects NaN rather than inventing a value for it`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            writer.reset()
            writer.writeFixed(Float.NaN, 0f, 1f, 8)
        }
        assertTrue(
            failure.message!!.contains("NaN"),
            "the message must name the problem, was: ${failure.message}",
        )
        assertFailsWith<IllegalArgumentException> { writer.reset(); writer.writeNorm8(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Q.Pos.quantise(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Q.Norm8.quantise(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Q.Fixed(0f, 1f, 0.1f).quantise(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Q.declared(12, -5f, 5f).quantise(Float.NaN) }
    }

    @Test
    fun `an angle rejects the values that have no place on a circle`() {
        // Not a clamp and not a wrap: an infinity is not a direction, and unlike a bounded
        // kind an angle has no bound to send it to, so it must fail loudly instead.
        assertFailsWith<IllegalArgumentException> { writer.reset(); writer.writeAngle16(Float.NaN) }
        assertFailsWith<IllegalArgumentException> {
            writer.reset(); writer.writeAngle16(Float.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            writer.reset(); writer.writeAngle16(Float.NEGATIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> { Q.Angle16.quantise(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Q.Angle16.quantise(Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { Q.Angle16.quantise(Float.NEGATIVE_INFINITY) }
    }

    @Test
    fun `Q Exact carries NaN, the infinities and negative zero unchanged`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0f, 0f)) {
            writer.reset()
            writer.writeQ(Q.Exact, value)
            assertEquals(32L, writer.bitsWritten)
            reader.reset()
            assertEquals(
                value.toRawBits(),
                reader.readQ(Q.Exact).toRawBits(),
                "Q.Exact must be bit-for-bit for $value",
            )
        }
        assertEquals(0f, Q.Exact.maxError)
    }

    @Test
    fun `an angle wraps rather than clamping, and zero and a full turn are the same bucket`() {
        val turn = TWO_PI.toFloat()
        assertEquals(quantiseAngle16(0f), quantiseAngle16(turn), "0 and 2pi are one angle")
        assertEquals(quantiseAngle16(0f), quantiseAngle16(-turn))
        assertEquals(quantiseAngle16(1f), quantiseAngle16(1f + turn * 3))
        assertEquals(0f, dequantiseAngle16(quantiseAngle16(turn)))

        // A hair below a full turn is a hair below zero, not the far side of the circle.
        val nearlyATurn = (TWO_PI - 1e-9).toFloat()
        assertTrue(angleDistance(dequantiseAngle16(quantiseAngle16(nearlyATurn)), nearlyATurn) <= Q.Angle16.maxError)
    }

    @Test
    fun `every angle bucket decodes inside one turn and is distinct`() {
        val turn = TWO_PI
        for (raw in 0 until ANGLE16_LEVELS step 97) {
            val angle = dequantiseAngle16(raw)
            assertTrue(angle >= 0f && angle < turn, "bucket $raw decoded to $angle")
            assertEquals(raw, quantiseAngle16(angle), "bucket $raw did not survive a round trip")
        }
    }

    @Test
    fun `a declared bit count resolves back to exactly that many bits`() {
        for (bits in 1..32) {
            for (min in listOf(-1024f, 0f, -0.5f, 100f)) {
                for (range in listOf(1f, 2048f, 0.25f, 5000f)) {
                    val q = Q.declared(bits, min, min + range)
                    assertEquals(bits, q.bits, "@Q(bits = $bits, min = $min, max = ${min + range})")
                    assertTrue(
                        q.actualStep <= q.step * 1.0000001f,
                        "resolved step ${q.actualStep} is coarser than the declared ${q.step}",
                    )
                }
            }
        }
    }

    @Test
    fun `a step declaration never resolves coarser than it asked for`() {
        val q = Q.Fixed(0f, 5000f, step = 1f)
        assertEquals(13, q.bits, "5000 units to the nearest unit needs 13 bits")
        assertTrue(q.actualStep <= 1f)

        val centimetres = Q.Fixed(-1024f, 1024f, step = 0.01f)
        assertTrue(centimetres.actualStep <= 0.01f)
        assertEquals(18, centimetres.bits)
    }

    @Test
    fun `every kind costs the bits it advertises and honours its bound after its own reduction`() {
        // Half these values are outside the kind's domain on purpose: the contract is that
        // the reduction happens first and the error bound holds after it, and a kind that
        // reduced the wrong way (an angle clamped, a position wrapped) is exactly what this
        // has to catch.
        val cases = listOf<Pair<Q, Float>>(
            Q.Norm8 to 0.37f,
            Q.Norm8 to 2f,
            Q.Norm8 to -1f,
            Q.Pos to -517.25f,
            Q.Pos to Q.Pos.MIN,
            Q.Pos to Q.Pos.MAX,
            Q.Pos to 1e9f,
            Q.Angle16 to 2.5f,
            Q.Angle16 to 7f,
            Q.Angle16 to -0.5f,
            Q.Exact to 12345.678f,
            Q.Fixed(-100f, -50f, step = 0.5f) to -73.25f,
        )
        for ((q, value) in cases) {
            writer.reset()
            writer.writeQ(q, value)
            assertEquals(q.bits.toLong(), writer.bitsWritten, "$q must cost ${q.bits} bits")
            reader.reset()
            val decoded = reader.readQ(q)
            val error = roundTripError(q, value, decoded)
            assertTrue(
                error <= q.maxError + Math.ulp(decoded),
                "$q round-tripped $value to $decoded, error $error over ${q.maxError}",
            )
        }
    }

    @Test
    fun `the wrap point is a seam, not a cliff`() {
        // The one place a clamp would be visibly wrong. 2pi minus a hair is next door to
        // zero, so the last bucket must be one step from the first and not a whole turn.
        val bucket = (TWO_PI / ANGLE16_LEVELS).toFloat()
        val lastBucket = dequantiseAngle16(ANGLE16_LEVELS - 1)
        assertTrue(
            angleDistance(lastBucket, dequantiseAngle16(0)) <= bucket + Math.ulp(lastBucket),
            "the last bucket ($lastBucket) must be one step from the first, not a turn",
        )

        // Rounding crosses the seam like any other bucket boundary: within half a bucket of
        // a full turn rounds up to bucket 0, further down stays in 65535.
        assertEquals(0, quantiseAngle16((TWO_PI - bucket / 3.0).toFloat()))
        assertEquals(ANGLE16_LEVELS - 1, quantiseAngle16((TWO_PI - bucket * 0.9).toFloat()))
    }

    @Test
    fun `a negative angle is a facing, not an underflow`() {
        // The clamp a bounded kind would apply is the specific wrong answer: -1 radian is
        // 5.28 radians, which is as far from zero as an angle gets.
        for (negative in listOf(-1e-4f, -1f, -3f, -6f, -(TWO_PI.toFloat() * 4f) - 1f)) {
            val decoded = Q.Angle16.dequantise(Q.Angle16.quantise(negative))
            assertTrue(
                decoded >= 0f && decoded < TWO_PI,
                "$negative decoded to $decoded, which is outside one turn",
            )
            assertTrue(
                angleDistance(decoded, negative) <= Q.Angle16.maxError + Math.ulp(negative),
                "$negative round-tripped to $decoded",
            )
        }
        assertTrue(
            Q.Angle16.dequantise(Q.Angle16.quantise(-1f)) > 5f,
            "-1 rad is 5.28 rad; anything near 0 means it was clamped instead of wrapped",
        )
    }

    @Test
    fun `an angle many turns out reduces to the same facing`() {
        // The wrap is arithmetic over the whole float range, not a mask that happens to work
        // for the turns that fit in 16 bits.
        for (turns in listOf(1, 2, 7, 100, -1, -3, -50)) {
            val far = (1.0 + turns * TWO_PI).toFloat()
            val decoded = Q.Angle16.dequantise(Q.Angle16.quantise(far))
            assertTrue(
                angleDistance(decoded, 1f) <= Q.Angle16.maxError + Math.ulp(far),
                "1 rad $turns turns out ($far) came back as $decoded",
            )
        }
    }

    @Test
    fun `a bounded kind clamps a far-outside value to the bound it passed`() {
        // Including the infinities: to a bounded kind an infinity is not a special case, it
        // is simply very out of range, and the bound is the honest answer.
        val negativeRange = Q.Fixed(-100f, -50f, step = 0.5f)
        assertEquals(1f, Q.Norm8.dequantise(Q.Norm8.quantise(1e9f)))
        assertEquals(0f, Q.Norm8.dequantise(Q.Norm8.quantise(-1e9f)))
        assertEquals(Q.Pos.MAX, Q.Pos.dequantise(Q.Pos.quantise(1e9f)))
        assertEquals(Q.Pos.MIN, Q.Pos.dequantise(Q.Pos.quantise(-1e9f)))
        assertEquals(Q.Pos.MAX, Q.Pos.dequantise(Q.Pos.quantise(Float.POSITIVE_INFINITY)))
        assertEquals(Q.Pos.MIN, Q.Pos.dequantise(Q.Pos.quantise(Float.NEGATIVE_INFINITY)))
        assertEquals(
            negativeRange.max,
            negativeRange.dequantise(negativeRange.quantise(0f)),
            "a wholly negative range clamps upward at its own max, not at zero",
        )
        assertEquals(negativeRange.min, negativeRange.dequantise(negativeRange.quantise(-1e9f)))
    }

    @Test
    fun `a wholly negative range round-trips like any other`() {
        // Nothing in the mapping may assume min is at or below zero; a range that never
        // crosses the origin is where a stray abs or sign assumption would show.
        val bits = 10
        val halfStep = 50.0 / ((1L shl bits) - 1L) / 2.0
        for (value in listOf(-100f, -99.999f, -75.5f, -50f)) {
            val decoded = roundTripFixed(value, -100f, -50f, bits)
            assertTrue(decoded in -100f..-50f, "$value decoded outside its own range: $decoded")
            assertTrue(
                abs(decoded - value) <= halfStep + Math.ulp(decoded),
                "$value round-tripped to $decoded",
            )
        }
    }

    @Test
    fun `one bit is a two-level field whose only levels are the bounds`() {
        // The narrowest legal width. 2^1 - 1 is one step, so there is nothing between the
        // bounds and everything rounds to the nearer of them.
        assertEquals(0, quantiseFixed(0f, 0f, 10f, 1))
        assertEquals(0, quantiseFixed(4.9f, 0f, 10f, 1))
        assertEquals(1, quantiseFixed(5.1f, 0f, 10f, 1))
        assertEquals(1, quantiseFixed(10f, 0f, 10f, 1))
        assertEquals(0f, dequantiseFixed(0, 0f, 10f, 1))
        assertEquals(10f, dequantiseFixed(1, 0f, 10f, 1))
        val q = Q.declared(bits = 1, min = 0f, max = 10f)
        assertEquals(1, q.bits)
        assertEquals(5f, q.maxError, "half the whole range is the honest error for one bit")
    }

    @Test
    fun `a thirty-two bit field keeps levels distinct across the raw Int sign boundary`() {
        // Level 2^31 is where the raw field becomes a negative Int. Consecutive levels either
        // side of it must stay ordered and distinct rather than collapsing or reordering.
        val levels = (1L shl 32) - 1L
        var previous = Float.NEGATIVE_INFINITY
        for (level in (levels / 2 - 2)..(levels / 2 + 2)) {
            val decoded = dequantiseFixed(level.toInt(), -1024f, 1024f, 32)
            assertTrue(decoded > previous, "level $level decoded to $decoded, not above $previous")
            previous = decoded
        }
    }

    @Test
    fun `a zero-width range is refused everywhere it could be used, naming the bounds`() {
        // A degenerate range has no steps to map onto: quantising into it would divide by
        // zero and hand back a NaN level rather than a diagnosis.
        val failure = assertFailsWith<IllegalArgumentException> { quantiseFixed(3f, 3f, 3f, 8) }
        assertTrue(
            failure.message!!.contains("3.0"),
            "the message must name the offending bounds, was: ${failure.message}",
        )
        assertFailsWith<IllegalArgumentException> { dequantiseFixed(0, 3f, 3f, 8) }
        assertFailsWith<IllegalArgumentException> { writer.reset(); writer.writeFixed(3f, 3f, 3f, 8) }
        assertFailsWith<IllegalArgumentException> { Q.Fixed(3f, 3f, 0.1f) }
        assertFailsWith<IllegalArgumentException> { Q.declared(8, 3f, 3f) }
    }

    @Test
    fun `Pos gives centimetre-scale precision for sixteen bits`() {
        assertEquals(16, Q.Pos.bits)
        assertTrue(Q.Pos.maxError < 0.02f, "a 2048 unit world in 16 bits should be under 2 cm")
        // 65535 steps is an odd number, so the midpoint of the range is not a level: the
        // origin comes back half a step off, which is the promise, not a bug.
        assertTrue(abs(Q.Pos.dequantise(Q.Pos.quantise(0f))) <= Q.Pos.maxError)
    }

    @Test
    fun `an impossible declaration is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { Q.Fixed(1f, 1f, 0.1f) }
        assertFailsWith<IllegalArgumentException> { Q.Fixed(2f, 1f, 0.1f) }
        assertFailsWith<IllegalArgumentException> { Q.Fixed(0f, 1f, 0f) }
        assertFailsWith<IllegalArgumentException> { Q.Fixed(0f, 1f, -1f) }
        assertFailsWith<IllegalArgumentException> { Q.Fixed(0f, Float.POSITIVE_INFINITY, 1f) }
        // 1e-12 over a range of 1 needs 40 bits, which the wire format does not have.
        assertFailsWith<IllegalArgumentException> { Q.Fixed(0f, 1f, 1e-12f) }
        assertFailsWith<IllegalArgumentException> { Q.declared(0, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { Q.declared(33, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { Q.declared(8, 1f, 0f) }
    }

    @Test
    fun `a thirty-two bit fixed field uses the whole unsigned range`() {
        // The top level is 0xFFFFFFFF, which is a negative Int. It must still write and read
        // as an unsigned field rather than saturating or sign-extending.
        val raw = quantiseFixed(1f, 0f, 1f, 32)
        assertEquals(-1, raw, "the top level of a 32-bit field is all ones")
        writer.reset()
        writer.writeFixed(1f, 0f, 1f, 32)
        assertEquals(32L, writer.bitsWritten)
        reader.reset()
        assertEquals(1f, reader.readFixed(0f, 1f, 32))
    }
    /**
     * `Q.declared` is what `@Q(bits, min, max)` resolves to, so a default on its bounds is the
     * same defect as a default on the annotation: `declared(12)` would compile into a silent
     * `[0, 1]` clamp. The annotation side is pinned in `udea-annotations`; this pins the
     * function, because re-adding a default there would compile cleanly and no call site in
     * the tree would notice — they all pass both bounds today.
     *
     * Kotlin compiles default arguments into a synthetic `<name>$default` bridge, so its
     * absence is what proves there are none.
     */
    @Test
    fun `declared has no default bounds to silently clamp a field into zero to one`() {
        val bridges = Q.Companion::class.java.declaredMethods.map { it.name }
            .filter { it.startsWith("declared") && it != "declared" }

        assertEquals(
            emptyList(),
            bridges,
            "Q.declared must take min and max explicitly; a default range is a wrong range " +
                "that compiles",
        )
        assertFalse(
            Q.Companion::class.java.declaredMethods.single { it.name == "declared" }.isSynthetic,
        )
    }
}
