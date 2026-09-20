package dev.wildware.udea.replay

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pointer a recording carries (issue #262): where on the ground the player was pointing, what
 * they were pointing at, the wheel, and the two ends of a drag.
 *
 * ## Why a recording has to carry it at all
 *
 * An RTS is driven by the mouse. "Move there", "build here", "attack that" are the *whole* of what a
 * player does, and every one of them is a world point or an entity that only presentation could work
 * out - the simulation never sees the camera, by design. A recording that stored only keys and sticks
 * would therefore replay a match in which nobody ever gave an order, and it would do it silently: the
 * arrays would be the right length and every value in range. So the pointer travels with the rest of
 * the input, on the same tick, through the same presence mask.
 *
 * ## What is deliberately *not* here
 *
 * A screen position. [InputSample] holds the world point presentation computed, never the pixel it
 * came from, because a recording of pixels would replay differently on another window size - and
 * because a pixel in a sample is a pixel one step away from the simulation.
 *
 * ## The byte-compatibility promise
 *
 * A recording with no pointer in it is written exactly as it was before this issue, byte for byte:
 * the pointer's presence bit is never set, and `ReplayByteCompatibilityTest`'s golden bytes are the
 * gate on that. [`a recording with a pointer is format 3 and one without is not`] is the other half -
 * a file an older build cannot read says so by its version number rather than by a mask it does not
 * recognise.
 */
class PointerSampleTest {

    private val schema = InputSchema(
        axes = listOf("game/move"),
        actions = listOf("game/attack", "game/stop"),
    )

    private val identity = BuildIdentity(
        rootSeed = 0x5EEDL,
        protoHash = 0x6062,
        assetGraphHash = ByteArray(8) { it.toByte() },
        inputSchemaHash = schema.hash,
    )

    private fun recorder(peers: Int = 1) = ReplayRecorder(
        identityWithoutSchema = identity,
        schema = schema,
        peerCount = peers,
        gameId = "pointer-test",
        gameVersion = "0.0.1",
    )

    @Test
    fun `a sample with nothing in it says the pointer is nowhere`() {
        val sample = InputSample(schema)

        assertFalse(sample.hasPointer, "a cleared sample claims a pointer")
        assertEquals(NetId.NONE, sample.pointerEntity, "a cleared sample claims something under the pointer")
        assertEquals(0f, sample.scroll, "a cleared sample claims a wheel turn")
        assertFalse(sample.dragStarted, "a cleared sample claims a drag began")
        assertFalse(sample.dragEnded, "a cleared sample claims a drag ended")
        assertTrue(sample.isIdle(), "a sample with no pointer and no keys is not idle")
    }

    @Test
    fun `a pointer makes a sample something rather than nothing`() {
        val sample = InputSample(schema)

        sample.setPointer(12.5f, -3.25f, NetId.of(index = 7, generation = 2))

        assertFalse(
            sample.isIdle(),
            "a tick in which the player was pointing at something counted as an idle tick, so the " +
                "recording would drop it",
        )
    }

    @Test
    fun `every part of the pointer survives an encode and a decode`() {
        val recorder = recorder()
        val slots = recorder.newSampleSlots()
        val entity = NetId.of(index = 41, generation = 3)
        for (index in 0 until TICKS) {
            val sample = slots[0]
            sample.clear()
            when (index % PATTERN) {
                // Nothing at all: the tick that must still cost one byte.
                0 -> Unit
                // Hovering, over nothing.
                1 -> sample.setPointer(index.toFloat(), -index.toFloat(), NetId.NONE)
                // Hovering over a unit: the "attack that" order.
                2 -> sample.setPointer(index * HALF, index * HALF, entity)
                // A wheel notch and nothing else: zooming without moving the mouse.
                3 -> sample.setScroll(-(index.toFloat()))
                // A drag begun.
                4 -> {
                    sample.setPointer(index.toFloat(), 0f, NetId.NONE)
                    sample.setDragStart(index.toFloat(), 1f)
                }
                // A drag ended: the box-select an RTS lives on.
                else -> {
                    sample.setPointer(index.toFloat(), 0f, NetId.NONE)
                    sample.setDragStart(index.toFloat(), 1f)
                    sample.setDragEnd(index + HALF, 2f)
                }
            }
            recorder.record(FIRST + index.toLong(), slots, index.toLong())
        }

        val original = recorder.seal()
        val decoded = ReplayRecording.decode(original.encode())

        val before = original.newSampleSlots()
        val after = decoded.newSampleSlots()
        var pointing = 0
        for (index in 0 until original.tickCount) {
            val tick = original.firstTick + index.toLong()
            original.samplesInto(tick, before)
            decoded.samplesInto(tick, after)
            if (before[0].hasPointer) pointing++
            assertTrue(
                before[0].contentEquals(after[0]),
                "$tick did not round trip: ${before[0]} became ${after[0]}",
            )
        }
        assertTrue(pointing > 0, "no tick in this recording carried a pointer, so nothing was tested")
        // Named fields as well as contentEquals, so a contentEquals that forgot the pointer cannot
        // make this test agree with itself.
        decoded.samplesInto(FIRST + 2L, after)
        assertEquals(1f, after[0].pointerX, "the pointer's x did not come back")
        assertEquals(1f, after[0].pointerY, "the pointer's y did not come back")
        assertEquals(entity, after[0].pointerEntity, "what was under the pointer did not come back")
        decoded.samplesInto(FIRST + 5L, after)
        assertTrue(after[0].dragEnded, "a drag that ended did not come back")
        assertEquals(5.5f, after[0].dragEndX, "the drag's end did not come back")
    }

    @Test
    fun `a recording with a pointer is format 3 and one without is not`() {
        val withPointer = recorder().let { recorder ->
            val slots = recorder.newSampleSlots()
            slots[0].setPointer(1f, 2f, NetId.NONE)
            recorder.record(FIRST, slots, 1L)
            recorder.seal().encode()
        }
        val without = recorder().let { recorder ->
            val slots = recorder.newSampleSlots()
            slots[0].setPressed(0, true)
            recorder.record(FIRST, slots, 1L)
            recorder.seal().encode()
        }

        assertEquals(
            ReplayFormat.FORMAT_VERSION,
            withPointer[ReplayFormat.MAGIC.size].toInt(),
            "a recording carrying a pointer was not written as the version that can express one",
        )
        assertEquals(
            ReplayFormat.EDITLESS_FORMAT_VERSION,
            without[ReplayFormat.MAGIC.size].toInt(),
            "a recording with no pointer stopped being the file every earlier build reads",
        )
    }

    @Test
    fun `a pointer off the edge of the world is refused rather than recorded`() {
        val sample = InputSample(schema)

        // A `NaN` reaching a recording is a divergence with no cause: it compares equal to nothing,
        // so every tick after it in a replay is a mismatch pointing at the wrong place.
        assertFailsWith<IllegalArgumentException> { sample.setPointer(Float.NaN, 0f, NetId.NONE) }
        assertFailsWith<IllegalArgumentException> { sample.setPointer(0f, Float.POSITIVE_INFINITY, NetId.NONE) }
        assertFailsWith<IllegalArgumentException> { sample.setScroll(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { sample.setDragStart(Float.NaN, 0f) }
        assertFailsWith<IllegalArgumentException> { sample.setDragEnd(0f, Float.NaN) }
    }

    @Test
    fun `a copied sample carries the pointer with it`() {
        val source = InputSample(schema).apply {
            setPointer(3f, 4f, NetId.of(index = 9, generation = 1))
            setScroll(2f)
            setDragStart(1f, 1f)
            setDragEnd(2f, 2f)
        }
        val copy = InputSample(schema)

        copy.copyFrom(source)

        assertTrue(copy.contentEquals(source), "a copy lost the pointer: $copy")
        assertEquals(NetId.of(index = 9, generation = 1), copy.pointerEntity)
    }

    @Test
    fun `two samples that differ only in the pointer are not the same sample`() {
        // `contentEquals` is what the round-trip gate above compares with. If it ignored the
        // pointer, every assertion in this file that leans on it would pass with the pointer
        // dropped on the floor.
        val one = InputSample(schema).apply { setPointer(3f, 4f, NetId.NONE) }
        val other = InputSample(schema).apply { setPointer(3f, 5f, NetId.NONE) }
        val entityDiffers = InputSample(schema).apply { setPointer(3f, 4f, NetId.of(index = 1, generation = 0)) }

        assertFalse(one.contentEquals(other), "two different pointer positions compared equal")
        assertFalse(one.contentEquals(entityDiffers), "two different picked entities compared equal")
    }

    private companion object {
        /** Not zero: a recording starts after the scene loads, and the code must not assume 0. */
        val FIRST: Tick = Tick(1)

        const val TICKS = 12
        const val PATTERN = 6
        const val HALF = 0.5f
    }
}
