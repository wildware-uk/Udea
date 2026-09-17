package dev.wildware.udea.net.wire

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.bits.MalformedBitStream
import dev.wildware.udea.net.bits.readVarInt
import dev.wildware.udea.net.bits.writeVarInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The framing defect from `packets.kt:66` and `:117`, made impossible.
 *
 * The old path allocated a fixed 2048-byte buffer per packet and handed all 2048 bytes to a
 * transport with a 1500-byte MTU, with no length prefix anywhere — so one datagram could carry
 * exactly one message and the receiver could not tell payload from padding.
 */
class FramingTest {

    private val header = PacketHeader(
        protoHash = 0xBEEF,
        seq = 7,
        ack = 3,
        ackBits = 0b1011,
        serverTick = Tick(1234),
        baselineTick = Tick(1200),
        hasBaseline = true,
    )

    @Test
    fun `three framed messages parse back to exactly three with correct boundaries`() {
        val buffer = ByteArray(256)
        val writer = BitBufferWriter(buffer)
        header.write(writer)
        val headerBits = writer.bitPosition
        val frames = FrameWriter(writer)

        frames.beginMessage(MessageType.Snapshot).writeVarInt(11)
        val first = frames.endMessage()
        frames.beginMessage(MessageType.Input).apply {
            writeVarInt(22)
            writeVarInt(33)
        }
        val second = frames.endMessage()
        frames.beginMessage(MessageType.ProtocolAdvert).writeLong(0x0123_4567_89AB_CDEFL)
        val third = frames.endMessage()

        val reader = FrameReader(buffer, 0, frames.byteLength, headerBits)
        val found = generateSequence { reader.next() }.toList()

        assertEquals(3, found.size, "a three-message datagram did not parse back as three messages")
        assertEquals(listOf(MessageType.Snapshot, MessageType.Input, MessageType.ProtocolAdvert), found.map { it.type })
        assertEquals(listOf(first, second, third), found.map { it.length })

        assertEquals(11, reader.readerFor(found[0]).readVarInt())
        val secondReader = reader.readerFor(found[1])
        assertEquals(22, secondReader.readVarInt())
        assertEquals(33, secondReader.readVarInt())
        assertEquals(0x0123_4567_89AB_CDEFL, reader.readerFor(found[2]).readLong())
    }

    @Test
    fun `a frame reader cannot read past its own message`() {
        val buffer = ByteArray(64)
        val writer = BitBufferWriter(buffer)
        header.write(writer)
        val headerBits = writer.bitPosition
        val frames = FrameWriter(writer)
        frames.beginMessage(MessageType.Snapshot).writeVarInt(5)
        frames.endMessage()
        frames.beginMessage(MessageType.Input).writeVarInt(6)
        frames.endMessage()

        val reader = FrameReader(buffer, 0, frames.byteLength, headerBits)
        val first = reader.next()!!
        val bound = reader.readerFor(first)
        assertEquals(5, bound.readVarInt())
        // The next message's bytes are physically adjacent. A reader bound to the first frame
        // must not be able to reach them: containment, not convention, is what stops one
        // message's misparse from consuming the next.
        assertTrue(bound.remainingBits < 8, "the reader could see past its own frame")
    }

    @Test
    fun `a length prefix that runs past the datagram is refused`() {
        val buffer = ByteArray(32)
        val writer = BitBufferWriter(buffer)
        header.write(writer)
        val headerBits = writer.bitPosition
        val frames = FrameWriter(writer)
        frames.beginMessage(MessageType.Snapshot).writeVarInt(1)
        frames.endMessage()
        val length = frames.byteLength

        // Corrupt the length prefix to claim more payload than the datagram holds.
        val prefixByte = ((headerBits + 7L) ushr 3).toInt() + 1
        buffer[prefixByte] = 0xFF.toByte()

        val reader = FrameReader(buffer, 0, length, headerBits)
        val failure = assertFailsWith<MalformedBitStream> { reader.next() }
        assertTrue(failure.message!!.contains("payload byte"), failure.message!!)
    }

    @Test
    fun `an unknown message type is refused rather than skipped`() {
        val buffer = ByteArray(32)
        val writer = BitBufferWriter(buffer)
        header.write(writer)
        val headerBits = writer.bitPosition
        val frames = FrameWriter(writer)
        frames.beginMessage(MessageType.Snapshot).writeVarInt(1)
        frames.endMessage()
        buffer[((headerBits + 7L) ushr 3).toInt()] = 99

        assertFailsWith<MalformedBitStream> { FrameReader(buffer, 0, frames.byteLength, headerBits).next() }
    }

    @Test
    fun `the header round trips every field`() {
        val buffer = ByteArray(32)
        val writer = BitBufferWriter(buffer)
        header.write(writer)
        assertEquals(header, PacketHeader.read(BitBufferReader(buffer, 0, writer.byteLength)))
    }

    @Test
    fun `a baseline newer than the tick it describes is refused`() {
        val buffer = ByteArray(32)
        val writer = BitBufferWriter(buffer)
        header.copy(serverTick = Tick(10), baselineTick = Tick(20)).write(writer)
        assertFailsWith<MalformedBitStream> { PacketHeader.read(BitBufferReader(buffer, 0, writer.byteLength)) }
    }

    @Test
    fun `sequence comparison survives the sixteen bit wrap`() {
        assertTrue(PacketHeader.isNewer(0, 65_535), "0 must be newer than 65535 across the wrap")
        assertTrue(!PacketHeader.isNewer(65_535, 0))
        assertTrue(PacketHeader.isNewer(5, 3))
        assertTrue(!PacketHeader.isNewer(3, 5))
    }

    @Test
    fun `an empty datagram yields no frames`() {
        val buffer = ByteArray(32)
        val writer = BitBufferWriter(buffer)
        header.write(writer)
        assertNull(FrameReader(buffer, 0, writer.byteLength, writer.bitPosition).next())
    }
}
