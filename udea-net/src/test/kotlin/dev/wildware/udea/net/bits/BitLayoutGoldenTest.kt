package dev.wildware.udea.net.bits

import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.MaskOps
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The bit layout, pinned as hex.
 *
 * A round-trip test cannot catch a layout change: swap the bit order, or spend nine bits on
 * a varint group, and write/read still agree with each other while disagreeing with every
 * build that came before. The wire format is a cross-version contract, so it is checked
 * against bytes on disk instead.
 *
 * Regenerate deliberately with `./gradlew :udea-net:test -Dupdate.goldens=true`, and treat
 * a diff in the regenerated file as a protocol break that needs a `net-protocol.lock` bump.
 */
class BitLayoutGoldenTest {

    @Test
    fun `the fixed write sequence produces the recorded bytes`() {
        val buffer = ByteArray(GOLDEN_BUFFER_BYTES)
        val writer = BitBufferWriter(buffer)
        writeGoldenSequence(writer)

        val actual = "bits=${writer.bitsWritten}\n" + buffer.toHex(writer.byteLength) + "\n"

        val golden = goldenFile()
        if (System.getProperty("update.goldens") == "true") {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            println("updated golden ${golden.absolutePath}")
        }
        if (!golden.exists()) {
            fail(
                "missing golden ${golden.absolutePath}. Regenerate with " +
                    "./gradlew :udea-net:test -Dupdate.goldens=true",
            )
        }
        assertEquals(
            golden.readText().replace("\r\n", "\n"),
            actual,
            "the bit layout changed. If that was deliberate it is a protocol break: " +
                "regenerate with ./gradlew :udea-net:test -Dupdate.goldens=true",
        )
    }

    @Test
    fun `the fixed write sequence reads back as the values that went in`() {
        val buffer = ByteArray(GOLDEN_BUFFER_BYTES)
        val writer = BitBufferWriter(buffer)
        writeGoldenSequence(writer)
        val reader = BitBufferReader(buffer, 0, writer.byteLength)
        readAndAssertGoldenSequence(reader)
        assertEquals(writer.bitsWritten, reader.bitsRead)
    }

    @Test
    fun `the golden bytes decode without ever seeing the writer`() {
        // The point of the fixture: bytes recorded by an older build must still decode.
        val golden = goldenFile()
        if (!golden.exists()) return
        val lines = golden.readText().trim().lines()
        val bits = lines[0].removePrefix("bits=").toLong()
        val bytes = lines[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(((bits + 7) / 8).toInt(), bytes.size, "recorded bit count and byte count disagree")
        val reader = BitBufferReader(bytes)
        readAndAssertGoldenSequence(reader)
        assertEquals(bits, reader.bitsRead)
    }

    private fun ByteArray.toHex(length: Int): String {
        val hex = StringBuilder(length * 2)
        for (i in 0 until length) hex.append(HEX[(this[i].toInt() ushr 4) and 0xF]).append(HEX[this[i].toInt() and 0xF])
        return hex.toString()
    }

    private fun goldenFile(): File =
        File(System.getProperty("udea.projectDir") ?: ".", GOLDEN_PATH)

    private companion object {
        const val GOLDEN_PATH = "src/test/resources/goldens/bit-layout.txt"
        const val GOLDEN_BUFFER_BYTES = 64
        const val HEX = "0123456789abcdef"

        /** `0b101101` as the opaque mask the codec actually takes. */
        val MASK_6: FieldMask = MaskOps.of(0, 2, 3, 5)

        /** A full-width mask, to pin the two-chunk framing path. */
        val MASK_64: FieldMask = MaskOps.fromWords(longArrayOf(0x0123_4567_89AB_CDEFL))

        /** A hit-points declaration: 0..5000 to the nearest point, which is 13 bits. */
        val HIT_POINTS = Q.Fixed(0f, 5000f, step = 1f)

        /** The `@Q(bits = 12, min = -100f, max = 100f)` form the annotation resolves to. */
        val DECLARED_12 = Q.declared(bits = 12, min = -100f, max = 100f)

        /**
         * Every primitive, in an order that crosses byte boundaries at awkward places.
         *
         * Written against the frozen `udea-core` interfaces rather than [BitBufferWriter],
         * so the fixture pins the *format* and not one implementation of it.
         */
        fun writeGoldenSequence(out: BitWriter) {
            out.writeBoolean(true)
            out.writeBoolean(false)
            out.writeBits(0b101, 3)
            out.writeBits(0x2A, 6)
            out.writeVarInt(0)
            out.writeVarInt(300)
            out.writeVarInt(-1)
            out.writeZigZag(-7)
            out.writeZigZag(64)
            out.writeMask(MASK_6, 6)
            out.writeMask(MASK_64, 64)
            out.writeNorm8(0.5f)
            out.writeAngle16(3.14159265f)
            out.writeFixed(12.5f, -100f, 100f, 12)
            out.writeQ(Q.Pos, 3.25f)
            out.writeQ(Q.Norm8, 1f)
            out.writeQ(Q.Angle16, -0.5f)
            out.writeQ(Q.Exact, 1.5f)
            out.writeQ(HIT_POINTS, 1234f)
            out.writeQ(DECLARED_12, -33.5f)
            out.alignToByte()
            out.writeInt(0x1234_5678)
            out.writeLong(-2L)
            out.writeFloat(-0f)
        }

        fun readAndAssertGoldenSequence(src: BitReader) {
            assertEquals(true, src.readBoolean())
            assertEquals(false, src.readBoolean())
            assertEquals(0b101, src.readBits(3))
            assertEquals(0x2A, src.readBits(6))
            assertEquals(0, src.readVarInt())
            assertEquals(300, src.readVarInt())
            assertEquals(-1, src.readVarInt())
            assertEquals(-7, src.readZigZag())
            assertEquals(64, src.readZigZag())
            assertEquals(MASK_6, src.readMask(6))
            assertEquals(MASK_64, src.readMask(64))
            assertNear(0.5f, src.readNorm8(), Q.Norm8.maxError)
            assertNearAngle(3.14159265f, src.readAngle16(), Q.Angle16.maxError)
            assertNear(12.5f, src.readFixed(-100f, 100f, 12), 200f / 4095f / 2f)
            assertNear(3.25f, src.readQ(Q.Pos), Q.Pos.maxError)
            assertEquals(1f, src.readQ(Q.Norm8))
            assertNearAngle(-0.5f, src.readQ(Q.Angle16), Q.Angle16.maxError)
            assertEquals(1.5f, src.readQ(Q.Exact))
            assertNear(1234f, src.readQ(HIT_POINTS), HIT_POINTS.maxError)
            assertNear(-33.5f, src.readQ(DECLARED_12), DECLARED_12.maxError)
            src.alignToByte()
            assertEquals(0x1234_5678, src.readInt())
            assertEquals(-2L, src.readLong())
            assertEquals((-0f).toRawBits(), src.readFloat().toRawBits())
        }

        fun assertNear(expected: Float, actual: Float, tolerance: Float) {
            assertTrue(
                kotlin.math.abs(actual - expected) <= tolerance + Math.ulp(actual),
                "expected $expected within $tolerance, was $actual",
            )
        }

        fun assertNearAngle(expected: Float, actual: Float, tolerance: Float) {
            assertTrue(
                angleDistance(actual, expected) <= tolerance + Math.ulp(actual),
                "expected angle $expected within $tolerance, was $actual",
            )
        }
    }
}
