package dev.wildware.udea.net.bits

import dev.wildware.udea.core.replication.MaskOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bit accounting, verified against hand-computed expectations.
 *
 * `bitsWritten` is not a diagnostic: the framing layer sizes the datagram from
 * [BitBufferWriter.byteLength], so an off-by-one here is an off-by-one on the wire. Every
 * expectation in this file is worked out in the comment beside it rather than read back
 * from the implementation.
 */
class BitPositionTest {

    @Test
    fun `bits written is exact for a sequence that starts and ends mid-byte`() {
        val writer = BitBufferWriter(ByteArray(16))

        writer.writeBoolean(true) //                       1 bit   -> 1
        writer.writeBits(0b101, 3) //                      3 bits  -> 4   (still inside byte 0)
        writer.writeVarInt(300) //         2 groups x 8 = 16 bits  -> 20  (ends mid-byte 2)
        writer.writeZigZag(-1) //          1 group  x 8 =  8 bits  -> 28
        writer.writeNorm8(0.5f) //                         8 bits  -> 36
        writer.writeAngle16(1f) //                        16 bits  -> 52
        writer.writeFixed(0f, -1f, 1f, 12) //             12 bits  -> 64
        writer.writeBits(0b11, 2) //                       2 bits  -> 66  (starts a 9th byte)

        assertEquals(66L, writer.bitsWritten)
        assertEquals(66L, writer.bitPosition, "the frozen BitWriter position must agree")
        assertEquals(9, writer.byteLength, "66 bits is 9 bytes with 6 bits of padding")
        assertEquals(128L - 66L, writer.remainingBits)
    }

    @Test
    fun `a reader consumes exactly the bits the writer produced`() {
        val buffer = ByteArray(16)
        val writer = BitBufferWriter(buffer)
        writer.writeBoolean(true)
        writer.writeBits(0b101, 3)
        writer.writeVarInt(300)
        writer.writeZigZag(-1)
        writer.writeNorm8(0.5f)
        writer.writeAngle16(1f)
        writer.writeFixed(0f, -1f, 1f, 12)
        writer.writeBits(0b11, 2)

        val reader = BitBufferReader(buffer, 0, writer.byteLength)
        assertEquals(true, reader.readBoolean())
        assertEquals(0b101, reader.readBits(3))
        assertEquals(300, reader.readVarInt())
        assertEquals(-1, reader.readZigZag())
        assertEquals(28L, reader.bitsRead, "the mid-byte checkpoint: 1 + 3 + 16 + 8")
        assertTrue(reader.readNorm8() in 0.49f..0.51f)
        assertTrue(angleDistance(reader.readAngle16(), 1f) <= Q.Angle16.maxError)
        assertTrue(reader.readFixed(-1f, 1f, 12) in -0.001f..0.001f)
        assertEquals(0b11, reader.readBits(2))
        assertEquals(66L, reader.bitsRead)
        // 6 bits of padding remain in the ninth byte, and nothing more.
        assertEquals(6L, reader.remainingBits)
    }

    @Test
    fun `bits are packed least significant first within each byte`() {
        // Hand-computed: 0b101 at bits 0..2, then 0b11 at bits 3..4 -> 0b00011101 = 0x1D.
        val buffer = ByteArray(1)
        val writer = BitBufferWriter(buffer)
        writer.writeBits(0b101, 3)
        writer.writeBits(0b11, 2)
        assertEquals(0x1D.toByte(), buffer[0])
        assertEquals(5L, writer.bitsWritten)
    }

    @Test
    fun `a value spanning a byte boundary keeps its low bits in the earlier byte`() {
        // 4 bits of padding, then 0xABCD in 16 bits: byte0 = 0xD0, byte1 = 0xBC, byte2 = 0x0A.
        val buffer = ByteArray(3)
        val writer = BitBufferWriter(buffer)
        writer.writeBits(0, 4)
        writer.writeBits(0xABCD, 16)
        assertEquals(0xD0.toByte(), buffer[0])
        assertEquals(0xBC.toByte(), buffer[1])
        assertEquals(0x0A.toByte(), buffer[2])
        assertEquals(0xABCD, BitBufferReader(buffer).let { it.readBits(4); it.readBits(16) })
    }

    @Test
    fun `align to byte pads to the next boundary and is a no-op when already there`() {
        val buffer = ByteArray(8)
        val writer = BitBufferWriter(buffer)
        writer.writeBits(0b111, 3)
        writer.alignToByte()
        assertEquals(8L, writer.bitsWritten)
        writer.alignToByte()
        assertEquals(8L, writer.bitsWritten, "aligning an aligned writer must write nothing")
        writer.writeBits(0b1, 1)
        writer.alignToByte()
        assertEquals(16L, writer.bitsWritten)

        val reader = BitBufferReader(buffer, 0, writer.byteLength)
        assertEquals(0b111, reader.readBits(3))
        reader.alignToByte()
        assertEquals(8L, reader.bitsRead)
        reader.alignToByte()
        assertEquals(8L, reader.bitsRead)
        assertEquals(0b1, reader.readBits(1))
        reader.alignToByte()
        assertEquals(16L, reader.bitsRead)
    }

    @Test
    fun `an empty mask costs zero bits`() {
        val writer = BitBufferWriter(ByteArray(8))
        writer.writeMask(MaskOps.EMPTY, 0)
        assertEquals(0L, writer.bitsWritten, "a zero-field mask must emit nothing at all")
        assertEquals(0, writer.byteLength)

        // And a mask costs one bit per field, not one byte.
        writer.writeMask(MaskOps.of(0, 2), 3)
        assertEquals(3L, writer.bitsWritten)
    }

    @Test
    fun `a sixty-four field mask survives the two-chunk path`() {
        val buffer = ByteArray(16)
        val writer = BitBufferWriter(buffer)
        writer.writeBits(0b1, 1) //                        deliberately unaligned
        writer.writeMask(MaskOps.fromWords(longArrayOf(-0x1234_5678_9ABC_DEF0L)), 64)
        assertEquals(65L, writer.bitsWritten)
        val reader = BitBufferReader(buffer, 0, writer.byteLength)
        assertEquals(0b1, reader.readBits(1))
        assertEquals(MaskOps.fromWords(longArrayOf(-0x1234_5678_9ABC_DEF0L)), reader.readMask(64))
    }

    @Test
    fun `reset rewinds without carrying stale bits from the previous payload`() {
        val buffer = ByteArray(8)
        val writer = BitBufferWriter(buffer)
        writer.writeLong(-1L)
        assertEquals(64L, writer.bitsWritten)

        writer.reset()
        assertEquals(0L, writer.bitsWritten)
        writer.writeBits(0, 8)
        // Byte 0 was 0xFF a moment ago. A writer that ORed into a recycled buffer would
        // leave it 0xFF and put eight bits of the last packet on the wire.
        assertEquals(0.toByte(), buffer[0])
        assertEquals(0xFF.toByte(), buffer[1], "bytes past the new payload are simply unread")
    }

    @Test
    fun `a writer can be rebound to a different buffer without allocating a new one`() {
        val first = ByteArray(4)
        val second = ByteArray(4)
        val writer = BitBufferWriter(first)
        writer.writeBits(0b1010, 4)

        writer.reset(second, 1, 2)
        assertEquals(16L, writer.capacityBits)
        assertEquals(0L, writer.bitsWritten)
        writer.writeBits(0xFF, 8)
        assertEquals(0.toByte(), second[0])
        assertEquals(0xFF.toByte(), second[1])
        assertEquals(0x0A.toByte(), first[0], "the old buffer is left exactly as it was")
    }
}
