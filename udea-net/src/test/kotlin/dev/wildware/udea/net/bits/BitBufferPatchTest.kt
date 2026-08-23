package dev.wildware.udea.net.bits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The two writer operations the framing layer needs: back-patching a length, and rolling back.
 *
 * Both exist so a datagram can be packed in one pass. Without [BitBufferWriter.patchBits] a
 * framing layer has to serialise each message twice — once to measure it — and without
 * [BitBufferWriter.truncateTo] a budgeted packer has to do the same for each entity. Two passes
 * over every entity every tick for every client is the difference between a send loop that
 * allocates nothing and one that needs a scratch buffer per message.
 */
class BitBufferPatchTest {

    @Test
    fun `a patched length prefix reads back as the patched value`() {
        val writer = BitBufferWriter(ByteArray(16))
        writer.writeBits(0xAB, 8)
        val lengthAt = writer.bitPosition
        writer.writeBits(0, 16)
        writer.writeBits(0xCD, 8)
        writer.patchBits(lengthAt, 0x1234, 16)

        val reader = writer.toReader()
        assertEquals(0xAB, reader.readBits(8))
        assertEquals(0x1234, reader.readBits(16))
        assertEquals(0xCD, reader.readBits(8))
    }

    @Test
    fun `patching leaves the write cursor where it was`() {
        val writer = BitBufferWriter(ByteArray(16))
        writer.writeBits(0, 16)
        writer.writeBits(0xFF, 8)
        val before = writer.bitPosition
        writer.patchBits(0, 0x00FF, 16)
        assertEquals(before, writer.bitPosition, "a patch moved the write cursor")
        assertEquals(3, writer.byteLength)
    }

    @Test
    fun `patching past what has been written is refused`() {
        val writer = BitBufferWriter(ByteArray(16))
        writer.writeBits(0, 8)
        // Patching a range that is not yet written would leave an unwritten gap that reads back
        // as whatever the recycled buffer held — the exact class of bug a reused MTU buffer makes
        // easy and a fresh allocation per packet hid.
        assertFailsWith<BitBufferOverflow> { writer.patchBits(8, 1, 8) }
    }

    @Test
    fun `patching a field that is not byte aligned is refused`() {
        val writer = BitBufferWriter(ByteArray(16))
        writer.writeBits(0, 3)
        writer.writeBits(0, 16)
        assertFailsWith<IllegalArgumentException> { writer.patchBits(3, 1, 16) }
    }

    @Test
    fun `truncation discards everything after the mark and leaves a valid prefix`() {
        val writer = BitBufferWriter(ByteArray(16))
        writer.writeBits(0xAB, 8)
        val mark = writer.bitPosition
        writer.writeBits(0xCD, 8)
        writer.writeBits(0xEF, 8)
        writer.truncateTo(mark)

        assertEquals(1, writer.byteLength)
        assertEquals(0xAB, writer.toReader().readBits(8))

        // And the discarded region is genuinely reusable: a later write must not OR into the
        // bytes the rolled-back entity left behind.
        writer.writeBits(0x12, 8)
        val reader = writer.toReader()
        assertEquals(0xAB, reader.readBits(8))
        assertEquals(0x12, reader.readBits(8))
        assertEquals(2, writer.byteLength)
    }

    @Test
    fun `truncating forwards is refused`() {
        val writer = BitBufferWriter(ByteArray(16))
        writer.writeBits(0, 8)
        assertFailsWith<IllegalArgumentException> { writer.truncateTo(16) }
    }
}
