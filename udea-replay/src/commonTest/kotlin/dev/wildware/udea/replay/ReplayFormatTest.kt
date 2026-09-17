package dev.wildware.udea.replay

import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `.udearep` container: what survives a round trip, and what a damaged file is refused with.
 *
 * Standards section 1 bans the order-dependent implicit contract that `PacketUtil` was built on -
 * components streamed in bag order with no type tag and no length prefix - so every check here is
 * about the *self-describing* half of the format doing its job: a truncated file, a flipped byte,
 * a wrong magic and a wrong version each produce a sentence naming what is wrong, rather than a
 * decode that runs off the end and a replay that diverges for no visible reason.
 */
class ReplayFormatTest {

    private val schema = InputSchema(
        axes = listOf("game/look", "game/move"),
        actions = listOf("game/attack", "game/dash", "game/jump"),
    )

    private val identity = BuildIdentity(
        rootSeed = 0x5EEDL,
        protoHash = 0x6062,
        assetGraphHash = ByteArray(32) { it.toByte() },
        inputSchemaHash = schema.hash,
    )

    private fun recorder(peers: Int = 2) = ReplayRecorder(
        identityWithoutSchema = identity,
        schema = schema,
        peerCount = peers,
        gameId = "format-test",
        gameVersion = "0.0.1",
    )

    /** A recording whose samples are varied enough that every presence bit is exercised. */
    private fun recorded(ticks: Int = 64, peers: Int = 2): ReplayRecording {
        val recorder = recorder(peers)
        val slots = recorder.newSampleSlots()
        for (index in 0 until ticks) {
            for (peer in slots.indices) {
                val sample = slots[peer]
                sample.clear()
                // Every fourth tick is fully idle, so the one-byte path is on the wire too.
                if (index % 4 != 0) {
                    sample.setAxis(index % schema.axisCount, (index - 30) / 30f, peer - 0.5f)
                    sample.setPressed((index + peer) % schema.actionCount, true)
                    sample.setPressCount(index % schema.actionCount, (index % 3))
                }
            }
            recorder.record(FIRST + index.toLong(), slots, index.toLong() * 0x100000001b3L)
        }
        return recorder.seal()
    }

    @Test
    fun `every sample and every hash survives an encode and a decode`() {
        val original = recorded()
        val decoded = ReplayRecording.decode(original.encode())

        assertEquals(original.header, decoded.header, "the header did not round trip")
        assertEquals(
            original.hashStream().toList(),
            decoded.hashStream().toList(),
            "the hash stream did not round trip",
        )
        val before = original.newSampleSlots()
        val after = decoded.newSampleSlots()
        var idleTicks = 0
        var busyTicks = 0
        for (index in 0 until original.tickCount) {
            val tick = original.firstTick + index.toLong()
            original.samplesInto(tick, before)
            decoded.samplesInto(tick, after)
            for (peer in before.indices) {
                assertTrue(
                    before[peer].contentEquals(after[peer]),
                    "$tick peer $peer changed: ${before[peer]} became ${after[peer]}",
                )
            }
            if (before[0].isIdle()) idleTicks++ else busyTicks++
        }
        // Both branches of the presence mask were on the wire, or this test only covered one.
        assertTrue(idleTicks > 0, "no idle tick was recorded, so the one-byte path is untested")
        assertTrue(busyTicks > 0, "no busy tick was recorded, so the full path is untested")
    }

    @Test
    fun `encoding is deterministic`() {
        val recording = recorded()
        assertTrue(
            recording.encode().contentEquals(recording.encode()),
            "the same recording encoded to two different byte sequences",
        )
    }

    @Test
    fun `a truncated file is refused as truncated`() {
        val bytes = recorded().encode()
        val failure = assertFailsWith<ReplayFormatException> {
            ReplayRecording.decode(bytes.copyOf(bytes.size - 9))
        }
        assertTrue(
            "truncated" in failure.message!! || "damaged" in failure.message!!,
            "a truncated file was refused with: ${failure.message}",
        )
    }

    @Test
    fun `a flipped byte is caught by the CRC`() {
        val bytes = recorded().encode()
        // Inside the frame section, well past the header and well before the trailer.
        val at = bytes.size / 2
        bytes[at] = (bytes[at].toInt() xor 0x40).toByte()
        val failure = assertFailsWith<ReplayFormatException> { ReplayRecording.decode(bytes) }
        assertTrue("damaged" in failure.message!!, "expected a CRC refusal, got: ${failure.message}")
    }

    @Test
    fun `a file that is not a udearep is refused by its magic`() {
        val bytes = ByteArray(64) { 'x'.code.toByte() }
        val failure = assertFailsWith<ReplayFormatException> { ReplayRecording.decode(bytes) }
        assertTrue(
            "not a .udearep" in failure.message!!,
            "expected a magic refusal, got: ${failure.message}",
        )
    }

    @Test
    fun `a future format version is refused before any other field is read`() {
        val bytes = recorded().encode()
        // The version is the u16 straight after the magic. Bump it and re-stamp the CRC, so the
        // file is otherwise perfectly valid: the point is that the *version* is what stops it,
        // and not the corruption that a naive edit would also introduce.
        bytes[ReplayFormat.MAGIC.size] = (ReplayFormat.FORMAT_VERSION + 1).toByte()
        val end = bytes.size - ReplayRecording.CRC_BYTES
        val crc = ReplayFormat.crc32(bytes, end).toInt()
        for (index in 0 until 4) bytes[end + index] = (crc ushr (index * 8)).toByte()

        val failure = assertFailsWith<ReplayFormatException> { ReplayRecording.decode(bytes) }
        assertTrue(
            "format ${ReplayFormat.FORMAT_VERSION + 1}" in failure.message!!,
            "expected the version in the refusal, got: ${failure.message}",
        )
    }

    @Test
    fun `a recorder refuses a tick that is not the next one`() {
        val recorder = recorder(peers = 1)
        val slots = recorder.newSampleSlots()
        recorder.record(FIRST, slots, 1L)
        recorder.record(FIRST + 1L, slots, 2L)

        val skipped = assertFailsWith<IllegalStateException> {
            recorder.record(FIRST + 3L, slots, 3L)
        }
        assertTrue("append-only" in skipped.message!!, skipped.message!!)

        val repeated = assertFailsWith<IllegalStateException> {
            recorder.record(FIRST + 1L, slots, 3L)
        }
        assertTrue("append-only" in repeated.message!!, repeated.message!!)
    }

    @Test
    fun `an empty recorder refuses to seal`() {
        val failure = assertFailsWith<IllegalStateException> { recorder().seal() }
        assertTrue("nothing was recorded" in failure.message!!, failure.message!!)
    }

    @Test
    fun `a sealed recorder refuses further ticks`() {
        val recorder = recorder(peers = 1)
        val slots = recorder.newSampleSlots()
        recorder.record(FIRST, slots, 1L)
        recorder.seal()
        assertFailsWith<IllegalStateException> { recorder.record(FIRST + 1L, slots, 2L) }
    }

    @Test
    fun `seeking outside the recording is refused rather than clamped`() {
        val recording = recorded(ticks = 8, peers = 1)
        val slots = recording.newSampleSlots()
        assertFailsWith<IllegalArgumentException> { recording.samplesInto(FIRST - 1L, slots) }
        assertFailsWith<IllegalArgumentException> { recording.samplesInto(recording.endTick, slots) }
        assertFalse(recording.endTick in recording.header, "endTick is exclusive")
        assertTrue(recording.firstTick in recording.header, "firstTick is inclusive")
    }

    @Test
    fun `a press count past the format's width is refused rather than truncated`() {
        val sample = InputSample(schema)
        assertFailsWith<IllegalArgumentException> {
            sample.setPressCount(0, InputSample.MAX_PRESSES + 1)
        }
    }

    @Test
    fun `an input schema hashes over its names and their lengths`() {
        val moved = InputSchema(axes = schema.axes.reversed(), actions = schema.actions)
        assertTrue(moved.hash != schema.hash, "reordering the axes did not change the hash")

        val split = InputSchema(axes = listOf("ab", "c"), actions = emptyList())
        val joined = InputSchema(axes = listOf("a", "bc"), actions = emptyList())
        assertTrue(
            split.hash != joined.hash,
            "['ab','c'] and ['a','bc'] hash the same, so the names are being concatenated " +
                "without their lengths and a rebind could go unnoticed",
        )
    }

    private companion object {
        /** Not zero: a recording starts after the scene loads, and the code must not assume 0. */
        val FIRST: Tick = Tick(1)
    }
}
