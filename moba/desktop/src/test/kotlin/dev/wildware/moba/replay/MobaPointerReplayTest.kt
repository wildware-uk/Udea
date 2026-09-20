package dev.wildware.moba.replay

import dev.wildware.moba.MobaControls
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.replay.ReplayFormat
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.readFrom
import dev.wildware.udea.replay.writeTo
import java.nio.file.Files
import java.util.Random
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A replay reproduces the same orders** (issue #262, the second half of its second criterion).
 *
 * `MobaReplayProofTest` proves a recorded match replays bit-identically, and it is the stronger test
 * of the two - but it could not see this. Its pilot pushes a stick and hits keys, and a world hash
 * says nothing about a field of the input that no system reads yet, so the pointer could be dropped
 * on the floor between the recorder and the replay and every hash would still agree. That is the
 * shape of an assertion that cannot fail, and this file is the answer to it: it compares the
 * **orders themselves**, tick by tick, across a real file on disk.
 *
 * ## What is really being exercised
 *
 * The whole production path and nothing stubbed:
 *
 * ```
 * Intent -> RecordingIntentSource -> MobaReplay.capture -> ReplayRecorder -> encode
 *        -> a .udearep on disk
 *        -> decode -> ReplayIntentSource -> MobaReplay.apply -> Intent
 * ```
 *
 * The pilot is seeded from the wall clock, for `MobaReplayProofTest`'s reason: it is the one thing a
 * replay categorically cannot reconstruct, so the only route from the recording run to the replay
 * run is the file. A test whose pointer positions were a function of the tick number would pass with
 * the recording deleted.
 */
class MobaPointerReplayTest {

    /**
     * A player driving with the mouse: pointing somewhere on the ground every tick, sometimes at a
     * unit, sometimes turning the wheel, and dragging a selection box now and then.
     *
     * The world points are *not* a function of the tick: they come from the wall-clock-seeded rng.
     */
    private class MousePilot(private val rng: Random) : IntentSource {

        /** What this pilot did, tick by tick, so the replay can be compared with it. */
        val given = ArrayList<Order>()

        override fun sample(into: Intent) {
            val order = Order(
                pointerX = span(),
                pointerY = span(),
                entity = if (rng.nextInt(OVER_A_UNIT_ONE_IN) == 0) {
                    NetId.of(index = rng.nextInt(NetId.MAX_INDICES), generation = rng.nextInt(2))
                } else {
                    NetId.NONE
                },
                scroll = if (rng.nextInt(WHEEL_ONE_IN) == 0) rng.nextInt(3) - 1f else 0f,
                dragStart = if (rng.nextInt(DRAG_ONE_IN) == 0) span() to span() else null,
                dragEnd = if (rng.nextInt(DRAG_ONE_IN) == 0) span() to span() else null,
            )
            given += order
            into.setPointer(order.pointerX, order.pointerY, order.entity)
            if (order.scroll != 0f) into.setScroll(order.scroll)
            order.dragStart?.let { into.setDragStart(it.first, it.second) }
            order.dragEnd?.let { into.setDragEnd(it.first, it.second) }
            // A key as well, so this is a tick of real input rather than a pointer on its own.
            into.setPressed(MobaControls.ATTACK_ACTION, rng.nextBoolean())
        }

        /** A world coordinate somewhere across a lane-sized map, to a quarter of a unit. */
        private fun span(): Float = (rng.nextInt(MAP_SPAN * 4) - MAP_SPAN * 2) / 4f

        private companion object {
            const val MAP_SPAN = 60
            const val OVER_A_UNIT_ONE_IN = 3
            const val WHEEL_ONE_IN = 7
            const val DRAG_ONE_IN = 11
        }
    }

    /** One tick's worth of pointing, as the pilot meant it. */
    private data class Order(
        val pointerX: Float,
        val pointerY: Float,
        val entity: NetId,
        val scroll: Float,
        val dragStart: Pair<Float, Float>?,
        val dragEnd: Pair<Float, Float>?,
    )

    /** Reads an [Intent] back into an [Order], so the two ends are compared as one value. */
    private fun orderOf(intent: Intent) = Order(
        pointerX = intent.pointerX,
        pointerY = intent.pointerY,
        entity = intent.pointerEntity,
        scroll = intent.scroll,
        dragStart = if (intent.dragStarted) intent.dragStartX to intent.dragStartY else null,
        dragEnd = if (intent.dragEnded) intent.dragEndX to intent.dragEndY else null,
    )

    @Test
    fun `every order a player gave with the mouse comes back out of the recording`() {
        val pilot = MousePilot(Random(System.nanoTime()))
        val host = MobaReplay.bootHeadless()
        val source = RecordingIntentSource(pilot)
        val recorder = MobaReplay.recorder(host)
        val slots = recorder.newSampleSlots()
        val intent = Intent(MobaControls.BINDINGS.catalog)

        // Sample the pilot once per tick, exactly as `IntentSampleSystem` does, and record what
        // came out. No world is stepped here: this test is about the input stream, and the match
        // that carries it is `MobaReplayProofTest`'s.
        repeat(TICKS) { index ->
            intent.clear()
            source.sample(intent)
            slots[0].copyFrom(source.sample)
            recorder.record(host.tick + index.toLong(), slots, index.toLong())
        }

        val directory = Files.createTempDirectory("udea-pointer-replay")
        val path = directory.resolve("moba-pointer.udearep")
        recorder.seal().writeTo(path)
        val reloaded = ReplayRecording.readFrom(path)

        val replayed = ReplayIntentSource()
        val back = Intent(MobaControls.BINDINGS.catalog)
        val slotsBack = reloaded.newSampleSlots()
        val gotBack = ArrayList<Order>()
        for (index in 0 until reloaded.tickCount) {
            reloaded.samplesInto(reloaded.firstTick + index.toLong(), slotsBack)
            replayed.pending.copyFrom(slotsBack[0])
            back.clear()
            replayed.sample(back)
            assertTrue(back.hasPointer, "tick $index replayed with no pointer at all")
            gotBack += orderOf(back)
        }

        println(
            "[pointer replay] ${pilot.given.size} order(s) recorded, ${path.fileSize()} bytes on " +
                "disk, ${pilot.given.count { it.entity != NetId.NONE }} of them over a unit",
        )
        assertEquals(TICKS, pilot.given.size, "the pilot was not sampled once per tick")
        assertTrue(
            pilot.given.any { it.entity != NetId.NONE },
            "no tick in this run pointed at a unit, so the entity half of the criterion was not tested",
        )
        assertTrue(
            pilot.given.any { it.dragEnd != null },
            "no tick in this run ended a drag, so the drag half was not tested",
        )
        assertEquals(pilot.given.size, gotBack.size, "a tick went missing between the recording and the replay")
        // Tick by tick rather than list against list: a 240-element mismatch message names nothing,
        // and the tick an order changed on is the only fact worth having when one does.
        for (index in pilot.given.indices) {
            assertEquals(pilot.given[index], gotBack[index], "tick $index replayed a different order")
        }
    }

    @Test
    fun `a recording of mouse orders declares the format that can hold them`() {
        // The version rule, checked on a real `moba` recording rather than on a synthetic one: a
        // file carrying a pointer must say so in its version, or a build from before issue #262
        // would read it, find no pointer where the mask says there is one, and replay a match with
        // no orders in it.
        val host = MobaReplay.bootHeadless()
        val recorder = MobaReplay.recorder(host)
        val slots = recorder.newSampleSlots()
        val intent = Intent(MobaControls.BINDINGS.catalog).apply { setPointer(3f, 4f, NetId.NONE) }
        MobaReplay.capture(intent, slots[0])
        recorder.record(host.tick, slots, 1L)

        val bytes = recorder.seal().encode()

        assertEquals(
            ReplayFormat.FORMAT_VERSION,
            bytes[ReplayFormat.MAGIC.size].toInt(),
            "a moba recording carrying mouse orders did not declare the format that can express them",
        )
    }

    private companion object {
        /** Long enough that the rng reaches every branch of the pilot several times over. */
        const val TICKS = 240
    }
}
