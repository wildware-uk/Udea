package dev.wildware.udea.net.bits

import dev.wildware.udea.core.replication.MaskOps
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the stream does when it runs out of room, runs off the end, or is handed nonsense.
 *
 * This is the half of the old `packets.kt` that did not exist: a fixed 2048-byte buffer
 * written in full with no length prefix cannot overflow, cannot underflow, and cannot
 * detect a truncated peer — it just produces a plausible, wrong entity.
 */
class BitBufferLimitsTest {

    @Test
    fun `overflow names the field and leaves the buffer untouched`() {
        val buffer = ByteArray(2)
        val writer = BitBufferWriter(buffer)
        writer.currentField = "transform.position.x"
        writer.writeBits(0b1010_1010_1010, 12)

        val before = buffer.copyOf()
        val failure = assertFailsWith<BitBufferOverflow> { writer.writeBits(0xFF, 8) }

        assertEquals("transform.position.x", failure.field)
        assertEquals(8, failure.requestedBits)
        assertEquals(12L, failure.bitPosition)
        assertEquals(16L, failure.capacityBits)
        assertTrue(
            failure.message!!.contains("transform.position.x"),
            "the message must name the field: ${failure.message}",
        )
        assertContentEquals(
            before,
            buffer,
            "a failed write must not have touched the buffer: the prefix is still sendable",
        )
        assertEquals(12L, writer.bitsWritten, "a failed write must not advance the position")
    }

    @Test
    fun `the prefix written before an overflow is still readable`() {
        val buffer = ByteArray(3)
        val writer = BitBufferWriter(buffer)
        writer.writeVarInt(300)
        writer.writeBits(0b101, 3)
        val goodBits = writer.bitsWritten
        assertFailsWith<BitBufferOverflow> { writer.writeInt(1) }

        val reader = BitBufferReader(buffer, 0, writer.byteLength)
        assertEquals(300, reader.readVarInt())
        assertEquals(0b101, reader.readBits(3))
        assertEquals(goodBits, reader.bitsRead)
    }

    @Test
    fun `an overflow with no field set still reports where and how much`() {
        val writer = BitBufferWriter(ByteArray(1))
        val failure = assertFailsWith<BitBufferOverflow> { writer.writeLong(0L) }
        assertNull(failure.field)
        assertEquals(64, failure.requestedBits)
        assertEquals(0L, failure.bitPosition)
        assertTrue(failure.message!!.contains("64"))
    }

    @Test
    fun `every write path checks capacity`() {
        assertFailsWith<BitBufferOverflow> { BitBufferWriter(ByteArray(0)).writeBoolean(true) }
        assertFailsWith<BitBufferOverflow> { BitBufferWriter(ByteArray(1)).writeInt(0) }
        assertFailsWith<BitBufferOverflow> { BitBufferWriter(ByteArray(2)).writeFloat(0f) }
        assertFailsWith<BitBufferOverflow> { BitBufferWriter(ByteArray(1)).writeAngle16(1f) }
        assertFailsWith<BitBufferOverflow> { BitBufferWriter(ByteArray(4)).writeMask(MaskOps.ALL, 64) }
        assertFailsWith<BitBufferOverflow> {
            val writer = BitBufferWriter(ByteArray(1))
            // 5 groups of 8 bits does not fit in one byte.
            writer.writeVarInt(-1)
        }
    }

    @Test
    fun `reading past the end throws instead of returning zeroes`() {
        val buffer = byteArrayOf(0xFF.toByte())
        val reader = BitBufferReader(buffer)
        assertEquals(0b1111, reader.readBits(4))

        val failure = assertFailsWith<BitBufferUnderflow> { reader.readBits(8) }
        assertEquals(8, failure.requestedBits)
        assertEquals(4L, failure.bitPosition)
        assertEquals(8L, failure.limitBits)
        assertEquals(4L, reader.bitsRead, "a failed read must not consume anything")

        // The four bits that are genuinely there are still readable afterwards.
        assertEquals(0b1111, reader.readBits(4))
        assertEquals(0L, reader.remainingBits)
    }

    @Test
    fun `a truncated payload fails rather than decoding into a plausible component`() {
        val full = ByteArray(16)
        val writer = BitBufferWriter(full)
        writer.writeQ(Q.Pos, 12.5f)
        writer.writeQ(Q.Pos, -30.25f)
        writer.writeQ(Q.Angle16, 1.25f)
        val truncated = BitBufferReader(full, 0, writer.byteLength - 2)
        truncated.readQ(Q.Pos)
        truncated.readQ(Q.Pos)
        assertFailsWith<BitBufferUnderflow> { truncated.readQ(Q.Angle16) }
    }

    @Test
    fun `a varint that never terminates is rejected, not followed off the end`() {
        val buffer = ByteArray(8) { 0xFF.toByte() }
        val reader = BitBufferReader(buffer)
        assertFailsWith<MalformedBitStream> { reader.readVarInt() }
    }

    @Test
    fun `a varint that decodes past 32 bits is rejected`() {
        val buffer = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10)
        val reader = BitBufferReader(buffer)
        assertFailsWith<MalformedBitStream> { reader.readVarInt() }
    }

    @Test
    fun `the largest legal varint is accepted`() {
        val buffer = ByteArray(8)
        val writer = BitBufferWriter(buffer)
        writer.writeVarInt(-1)
        assertEquals(40L, writer.bitsWritten, "0xFFFFFFFF is five groups")
        assertEquals(-1, writer.toReader().readVarInt())
    }

    @Test
    fun `an illegal bit count is refused by both sides`() {
        val writer = BitBufferWriter(ByteArray(8))
        assertFailsWith<IllegalArgumentException> { writer.writeBits(1, 0) }
        assertFailsWith<IllegalArgumentException> { writer.writeBits(1, 33) }
        assertFailsWith<IllegalArgumentException> { writer.writeBits(1, -1) }
        val reader = BitBufferReader(ByteArray(8))
        assertFailsWith<IllegalArgumentException> { reader.readBits(0) }
        assertFailsWith<IllegalArgumentException> { reader.readBits(33) }
        assertFailsWith<IllegalArgumentException> { writer.writeMask(MaskOps.EMPTY, 65) }
        assertFailsWith<IllegalArgumentException> { reader.readMask(65) }
    }

    @Test
    fun `a slice outside the backing array is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { BitBufferWriter(ByteArray(4), -1, 2) }
        assertFailsWith<IllegalArgumentException> { BitBufferWriter(ByteArray(4), 0, 5) }
        assertFailsWith<IllegalArgumentException> { BitBufferWriter(ByteArray(4), 3, 2) }
        assertFailsWith<IllegalArgumentException> { BitBufferReader(ByteArray(4), 2, 3) }
    }

    @Test
    fun `a writer only ever touches its own slice`() {
        val buffer = ByteArray(8) { 0x5A }
        val writer = BitBufferWriter(buffer, offset = 2, length = 3)
        assertEquals(24L, writer.capacityBits)
        writer.writeBits(-1, 24)
        assertFailsWith<BitBufferOverflow> { writer.writeBoolean(true) }
        assertContentEquals(
            byteArrayOf(0x5A, 0x5A, -1, -1, -1, 0x5A, 0x5A, 0x5A),
            buffer,
            "the bytes on either side of the slice belong to the framing layer",
        )
        assertEquals(0xFFFFFF, BitBufferReader(buffer, offset = 2, length = 3).readBits(24))
    }

    @Test
    fun `a framing layer can catch all three failures as one type`() {
        val failures = mutableListOf<BitBufferException>()
        try {
            BitBufferWriter(ByteArray(0)).writeBoolean(true)
        } catch (e: BitBufferException) {
            failures += e
        }
        try {
            BitBufferReader(ByteArray(0)).readBoolean()
        } catch (e: BitBufferException) {
            failures += e
        }
        try {
            BitBufferReader(ByteArray(8) { 0xFF.toByte() }).readVarInt()
        } catch (e: BitBufferException) {
            failures += e
        }
        assertEquals(3, failures.size, "all three must be catchable as BitBufferException")
        assertTrue(failures[0] is BitBufferOverflow)
        assertTrue(failures[1] is BitBufferUnderflow)
        assertTrue(failures[2] is MalformedBitStream)
    }
}
