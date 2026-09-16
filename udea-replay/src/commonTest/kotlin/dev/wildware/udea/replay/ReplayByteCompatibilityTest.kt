package dev.wildware.udea.replay

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `.udearep` written on any target is the file `master`'s JVM build writes, byte for byte.
 *
 * Issue #206: the format stays byte-compatible across the port, and this is the half of that
 * promise every target can check for itself. It runs on the JVM, on Android's host JVM and on
 * Wasm, so a target whose encoder or checksum disagrees with the JVM's fails here by name rather
 * than as a recording some other machine refuses as damaged.
 *
 * [MASTER_BYTES] was not written by this module. It is what `ReplayRecording.encode()` produced
 * on `origin/master` at `409c044`, with its checksum from `java.util.zip.CRC32`, for exactly the
 * recording [recordGolden] builds - a small one, so it can sit in a source file that Wasm reads
 * without a filesystem. Regenerating it from this branch would make the test agree with itself;
 * it only means something while it comes from the build the files already in the world came from.
 */
class ReplayByteCompatibilityTest {

    @Test
    fun `the checksum is CRC-32 as zip and every existing recording computes it`() {
        // The standard check value for CRC-32/ISO-HDLC, the variant `java.util.zip.CRC32` is.
        val check = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926L, ReplayFormat.crc32(check, check.size))
        assertEquals(0L, ReplayFormat.crc32(ByteArray(0), 0))
        // Only the first `end` bytes count: the trailer is not part of what it covers.
        assertEquals(0xCBF43926L, ReplayFormat.crc32(check + byteArrayOf(1, 2, 3), check.size))
    }

    @Test
    fun `a recording encodes to the bytes master's build wrote`() {
        assertEquals(MASTER_BYTES, hex(recordGolden().encode()))
    }

    @Test
    fun `a recording master's build wrote decodes, and every sample and hash is the one recorded`() {
        val fromMaster = ReplayRecording.decode(unhex(MASTER_BYTES))
        val rebuilt = recordGolden()

        assertEquals(rebuilt.header, fromMaster.header)
        assertEquals(rebuilt.hashStream().toList(), fromMaster.hashStream().toList())
        val expected = rebuilt.newSampleSlots()
        val actual = fromMaster.newSampleSlots()
        for (index in 0 until rebuilt.tickCount) {
            val tick = rebuilt.firstTick + index.toLong()
            rebuilt.samplesInto(tick, expected)
            fromMaster.samplesInto(tick, actual)
            for (peer in expected.indices) {
                assertTrue(expected[peer].contentEquals(actual[peer]), "$tick peer $peer: ${actual[peer]}")
            }
        }
        // Raw float bits, not float equality: -0.0f == 0.0f, and the format keeps the sign.
        fromMaster.samplesInto(FIRST + 1L, actual)
        assertEquals((-0.0f).toRawBits(), actual[0].axisY(0).toRawBits())
        assertEquals(1, actual[1].pressCount(1))
    }

    private fun recordGolden(): ReplayRecording {
        val schema = InputSchema(axes = listOf("game/move"), actions = listOf("game/attack", "game/jump"))
        val recorder = ReplayRecorder(
            identityWithoutSchema = BuildIdentity(
                rootSeed = 0x5EEDL,
                protoHash = 0x6062,
                assetGraphHash = ByteArray(4) { (it * 17).toByte() },
                inputSchemaHash = 0L,
            ),
            schema = schema,
            peerCount = 2,
            gameId = "golden",
            gameVersion = "1",
        )
        val slots = recorder.newSampleSlots()
        for (index in 0 until 5) {
            for (peer in slots.indices) {
                val sample = slots[peer]
                sample.clear()
                // Odd ticks carry input and even ticks are idle, so both frame shapes are on the wire.
                if (index % 2 == 1) {
                    sample.setAxis(0, index * 0.25f, -peer.toFloat())
                    sample.setPressed((index + peer) % 2, true)
                    sample.setPressCount(index % 2, index)
                }
            }
            recorder.record(FIRST + index.toLong(), slots, (index + 1).toLong() * 0x100000001b3L)
        }
        return recorder.seal()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {

        val FIRST: Tick = Tick(7)

        /** `origin/master` `409c044`'s encoding of [recordGolden], as lowercase hex. */
        const val MASTER_BYTES: String =
            "554445415245501a01005f000000ed5e000000000000626000000400112233e59c3981ddf25ba93c0000" +
                "00070000000000000005000000020000000600676f6c64656e0100310100090067616d652f6d6f76" +
                "6502000b0067616d652f61747461636b090067616d652f6a756d700000070000803e000000800200" +
                "01070000803e000080bf0100010000070000403f00000080020003070000403f000080bf01000300" +
                "00b30100000001000066030000000200001905000000030000cc060000000400007f080000000500" +
                "004225ab09"
    }
}
