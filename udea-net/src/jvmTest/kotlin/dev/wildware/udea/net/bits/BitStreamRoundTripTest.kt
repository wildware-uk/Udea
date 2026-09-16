package dev.wildware.udea.net.bits

import dev.wildware.udea.core.replication.MaskOps
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The round-trip property, over a million seeded values per primitive.
 *
 * Integer-shaped primitives must round-trip with **exact** equality; quantised ones must
 * land within the error their declaration promises and never outside it. A seeded
 * [Random] rather than a fuzzer so a failure is reproducible from the test name alone.
 */
class BitStreamRoundTripTest {

    private val buffer = ByteArray(128)
    private val writer = BitBufferWriter(buffer)
    private val reader = BitBufferReader(buffer)

    @Test
    fun `integer primitives round-trip exactly over a million values`() {
        val random = Random(0xB17_5EEDL)
        repeat(ITERATIONS) {
            val flag = random.nextBoolean()
            val bitCount = random.nextInt(1, 33)
            val bits = random.nextInt()
            val varInt = random.nextInt()
            val zigZag = random.nextInt()
            val int = random.nextInt()
            val long = random.nextLong()
            val float = Float.fromBits(random.nextInt())
            val fieldCount = random.nextInt(0, 65)
            val mask = MaskOps.fromWords(longArrayOf(random.nextLong()))

            writer.reset()
            writer.writeBoolean(flag)
            writer.writeBits(bits, bitCount)
            writer.writeVarInt(varInt)
            writer.writeZigZag(zigZag)
            writer.writeInt(int)
            writer.writeLong(long)
            writer.writeFloat(float)
            writer.writeMask(mask, fieldCount)

            reader.reset()
            assertEquals(flag, reader.readBoolean(), "boolean")
            assertEquals(
                (bits.toLong() and lowMask(bitCount)).toInt(),
                reader.readBits(bitCount),
                "writeBits($bits, $bitCount)",
            )
            assertEquals(varInt, reader.readVarInt(), "varint")
            assertEquals(zigZag, reader.readZigZag(), "zigzag")
            assertEquals(int, reader.readInt(), "int")
            assertEquals(long, reader.readLong(), "long")
            assertEquals(float.toRawBits(), reader.readFloat().toRawBits(), "float bit pattern")
            // MaskOps.and/lowest, never `MaskOps.word(mask, 0) and ...`: a FieldMask is opaque
            // (see FieldMask's KDoc), and unwrapping it to one Long here would be a test that
            // silently stops covering the whole mask the day the mask widens past 64 fields.
            assertEquals(
                MaskOps.and(mask, MaskOps.lowest(fieldCount)),
                reader.readMask(fieldCount),
                "mask",
            )

            assertEquals(
                writer.bitsWritten,
                reader.bitsRead,
                "reader must consume exactly what the writer produced",
            )
        }
    }

    @Test
    fun `fixed quantisation stays within half a step over a million values`() {
        val random = Random(0xF1DEEDL)
        repeat(ITERATIONS) {
            val bits = random.nextInt(1, 33)
            val min = random.nextDouble(-2000.0, 2000.0).toFloat()
            val range = Math.pow(10.0, random.nextDouble(-2.0, 4.0)).toFloat()
            val max = min + range
            // Deliberately overshoots both ends, so clamping is exercised on ~17% of values.
            val value = (min - range * 0.1 + random.nextDouble() * range * 1.2).toFloat()

            writer.reset()
            writer.writeFixed(value, min, max, bits)
            assertEquals(bits.toLong(), writer.bitsWritten, "a $bits-bit field must cost $bits bits")

            reader.reset()
            val decoded = reader.readFixed(min, max, bits)

            val clamped = value.coerceIn(min, max)
            val step = (max.toDouble() - min) / ((1L shl bits) - 1L)
            // Half a step is the quantisation promise; the ulp term is the unavoidable cost
            // of handing the result back as a Float, and only bites above ~24 bits.
            val tolerance = step / 2.0 + Math.ulp(decoded).toDouble()
            val error = abs(decoded.toDouble() - clamped)
            assertTrue(
                error <= tolerance,
                "value=$value min=$min max=$max bits=$bits decoded=$decoded " +
                    "error=$error exceeded tolerance=$tolerance",
            )
            assertTrue(
                decoded >= min && decoded <= max,
                "decoded $decoded escaped the declared range [$min, $max]",
            )
        }
    }

    @Test
    fun `angles round-trip within half a step of arc over a million values`() {
        val random = Random(0xA_9_1E_5EEDL)
        repeat(ITERATIONS) {
            // Well outside one turn on purpose: an angle wraps, it does not clamp.
            val value = random.nextDouble(-40.0, 40.0).toFloat()

            writer.reset()
            writer.writeAngle16(value)
            assertEquals(16L, writer.bitsWritten, "an angle must cost 16 bits")

            reader.reset()
            val decoded = reader.readAngle16()

            assertTrue(
                decoded >= 0f && decoded < TWO_PI.toFloat(),
                "decoded angle $decoded is outside [0, 2pi)",
            )
            // Half a bucket of arc, plus one ulp of the Float the bucket is handed back in.
            val tolerance = Q.Angle16.maxError.toDouble() + Math.ulp(decoded)
            val error = angleDistance(decoded, value).toDouble()
            assertTrue(
                error <= tolerance,
                "angle=$value decoded=$decoded error=$error exceeded tolerance=$tolerance",
            )
        }
    }

    @Test
    fun `norm8 round-trips within half a step over a million values`() {
        val random = Random(0xC0FFEEL)
        val tolerance = Q.Norm8.maxError.toDouble() + 1e-9
        repeat(ITERATIONS) {
            val value = (random.nextDouble() * 1.4 - 0.2).toFloat()

            writer.reset()
            writer.writeNorm8(value)
            assertEquals(8L, writer.bitsWritten, "a norm8 must cost 8 bits")

            reader.reset()
            val decoded = reader.readNorm8()

            val clamped = value.coerceIn(0f, 1f)
            val error = abs(decoded.toDouble() - clamped)
            assertTrue(
                error <= tolerance,
                "value=$value decoded=$decoded error=$error exceeded tolerance=$tolerance",
            )
        }
    }

    private companion object {
        /** The acceptance criterion is 1e6 seeded values per primitive. */
        const val ITERATIONS = 1_000_000
    }
}
