package dev.wildware.udea.net.bits

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    }

    @Test
    fun `an angle rejects the values that have no place on a circle`() {
        assertFailsWith<IllegalArgumentException> { writer.reset(); writer.writeAngle16(Float.NaN) }
        assertFailsWith<IllegalArgumentException> {
            writer.reset(); writer.writeAngle16(Float.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            writer.reset(); writer.writeAngle16(Float.NEGATIVE_INFINITY)
        }
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
    fun `the presets cost the bits they advertise and honour their own error bound`() {
        val cases = listOf<Pair<Q, Float>>(
            Q.Norm8 to 0.37f,
            Q.Pos to -517.25f,
            Q.Pos to Q.Pos.MIN,
            Q.Pos to Q.Pos.MAX,
            Q.Angle16 to 2.5f,
            Q.Exact to 12345.678f,
        )
        for ((q, value) in cases) {
            writer.reset()
            writer.writeQ(q, value)
            assertEquals(q.bits.toLong(), writer.bitsWritten, "$q must cost ${q.bits} bits")
            reader.reset()
            val decoded = reader.readQ(q)
            val error = if (q === Q.Angle16) angleDistance(decoded, value) else abs(decoded - value)
            assertTrue(
                error <= q.maxError + Math.ulp(decoded),
                "$q round-tripped $value to $decoded, error $error over ${q.maxError}",
            )
        }
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
}
