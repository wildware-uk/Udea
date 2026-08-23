package dev.wildware.udea.audio

import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueId
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The mixer, and the claim the whole module exists for: the cue queue is emptied.
 */
class CueAudioTest {

    private val hit = CueId(2)
    private val swoosh = CueId(3)

    private fun bindings(device: AudioDevice): AudioBindings = AudioBindings.of(
        listOf(
            CueSound(hit, "melee_hit", intArrayOf(device.load("a.ogg").slot), volume = 1F, pitchVariance = 0F),
            CueSound(
                swoosh,
                "melee_swoosh",
                intArrayOf(device.load("b.ogg").slot, device.load("c.ogg").slot),
                volume = 0.5F,
                pitchVariance = 0.25F,
            ),
        ),
    )

    /** A locator that puts everything at one fixed point. */
    private class FixedAt(val x: Float, val y: Float) : CueSourceLocator {
        override fun locate(source: NetId, out: FloatArray): Boolean {
            if (source.isNone) return false
            out[0] = x
            out[1] = y
            return true
        }
    }

    /**
     * The measurement the wave was asked for: queue depth over a thousand ticks, drained and not.
     *
     * Both halves are here because either alone proves nothing. The undrained half is the state
     * the game shipped in - it saturates at capacity and `droppedCount` climbs for the rest of the
     * run - and the drained half is what a mixer on the frame path does to it. A test that only
     * asserted the second would stay green if `CueQueue` were quietly given an unbounded backing
     * list, which would hide the same bug behind a leak.
     */
    @Test
    fun `an undrained queue saturates and a drained one stays empty`() {
        val ticks = 1000
        val cuesPerTick = 8

        val undrained = CueQueue()
        repeat(ticks) { tick ->
            repeat(cuesPerTick) { undrained.emit(Cue(hit, Tick(tick.toLong()), NetId.NONE)) }
        }
        assertEquals(
            CueQueue.DEFAULT_CAPACITY,
            undrained.size,
            "with nobody draining, the queue pins at capacity - this is the shipped behaviour",
        )
        assertEquals(
            (ticks.toLong() * cuesPerTick) - CueQueue.DEFAULT_CAPACITY,
            undrained.droppedCount,
            "every cue past the first ${CueQueue.DEFAULT_CAPACITY} was silently discarded",
        )

        val drained = CueQueue()
        val device = RecordingDevice()
        val audio = CueAudio(device, bindings(device), voiceCap = Int.MAX_VALUE, seed = 1L)
        var peak = 0
        repeat(ticks) { tick ->
            repeat(cuesPerTick) { drained.emit(Cue(hit, Tick(tick.toLong()), NetId.NONE)) }
            peak = maxOf(peak, drained.size)
            audio.drain(drained)
            assertEquals(0, drained.size, "the mixer leaves nothing behind on tick $tick")
        }
        assertEquals(0L, drained.droppedCount, "a drained queue drops nothing")
        assertEquals(cuesPerTick, peak, "depth never exceeds one tick's emissions")
        assertEquals(ticks.toLong() * cuesPerTick, audio.drained)
        assertEquals(ticks.toLong() * cuesPerTick, audio.played)
    }

    /** A silent device is still a drainer. This is what CI and an agent session run. */
    @Test
    fun `the silent device drains the queue and plays nothing`() {
        val queue = CueQueue()
        val audio = CueAudio(AudioDevice.Silent, bindings(RecordingDevice()), seed = 1L)
        repeat(4_000) { queue.emit(Cue(hit, Tick(it.toLong()), NetId.NONE)); audio.drain(queue) }
        assertEquals(0, queue.size)
        assertEquals(0L, queue.droppedCount)
        assertEquals(4_000L, audio.drained)
    }

    /** A cue nobody recorded audio for is drained, counted, and not an error. */
    @Test
    fun `an unbound cue is drained and counted`() {
        val queue = CueQueue()
        val device = RecordingDevice()
        val audio = CueAudio(device, bindings(device), seed = 1L)
        queue.emit(Cue(CueId(900), Tick(1L), NetId.NONE))
        assertEquals(1, audio.drain(queue))
        assertEquals(1L, audio.unbound)
        assertEquals(0L, audio.played)
        assertTrue(device.plays.isEmpty())
    }

    /** Distance attenuates, and past the falloff the mixer does not spend a voice at all. */
    @Test
    fun `a source past the falloff is not played and one at the ear is played at full volume`() {
        val device = RecordingDevice()
        val audio = CueAudio(
            device,
            bindings(device),
            listener = AudioListener(x = 0F, y = 0F, falloff = 10F),
            locator = FixedAt(100F, 0F),
            seed = 1L,
        )
        val far = CueQueue()
        far.emit(Cue(hit, Tick(1L), NetId.of(index = 1, generation = 0)))
        audio.drain(far)
        assertEquals(0L, audio.played, "100 units out with a 10 unit falloff is silence")
        assertEquals(1L, audio.suppressed)

        val near = CueAudio(
            device,
            bindings(device),
            listener = AudioListener(x = 100F, y = 0F, falloff = 10F),
            locator = FixedAt(100F, 0F),
            seed = 1L,
        )
        val queue = CueQueue()
        queue.emit(Cue(hit, Tick(1L), NetId.of(index = 1, generation = 0)))
        near.drain(queue)
        val play = assertNotNull(device.plays.lastOrNull())
        assertEquals(1F, play.volume, 1e-5F, "at the ear, the authored volume is the gain")
        assertEquals(0F, play.pan, 1e-5F)
    }

    /** Pan follows the sign of the offset, so a hit on the left is heard on the left. */
    @Test
    fun `pan is negative for a source left of the ear and positive for one to its right`() {
        fun panOf(sourceX: Float): Float {
            val device = RecordingDevice()
            val audio = CueAudio(
                device,
                bindings(device),
                listener = AudioListener(x = 0F, y = 0F, falloff = 100F, panWidth = 5F),
                locator = FixedAt(sourceX, 0F),
                seed = 1L,
            )
            val queue = CueQueue()
            queue.emit(Cue(hit, Tick(1L), NetId.of(index = 2, generation = 0)))
            audio.drain(queue)
            return assertNotNull(device.plays.singleOrNull()).pan
        }
        assertEquals(-1F, panOf(-40F), 1e-5F)
        assertEquals(1F, panOf(40F), 1e-5F)
        assertTrue(abs(panOf(0.5F)) < 0.5F)
    }

    /** Twenty-seven simultaneous hits are three voices, not twenty-seven. */
    @Test
    fun `the voice cap bounds one cue id per drain and resets on the next`() {
        val device = RecordingDevice()
        val audio = CueAudio(device, bindings(device), voiceCap = 3, seed = 1L)
        val queue = CueQueue()
        repeat(27) { queue.emit(Cue(hit, Tick(1L), NetId.NONE)) }
        repeat(27) { queue.emit(Cue(swoosh, Tick(1L), NetId.NONE)) }
        assertEquals(54, audio.drain(queue), "every cue is still taken off the queue")
        assertEquals(6L, audio.played, "three voices for each of two cue ids")
        assertEquals(48L, audio.suppressed)

        repeat(27) { queue.emit(Cue(hit, Tick(2L), NetId.NONE)) }
        audio.drain(queue)
        assertEquals(9L, audio.played, "the next drain gets its own three")
    }

    /** The authored pitch variance is the range the mixer uses, and it is clamped either side. */
    @Test
    fun `pitch varies within the authored fraction either side of unit pitch`() {
        val device = RecordingDevice()
        val audio = CueAudio(device, bindings(device), voiceCap = Int.MAX_VALUE, seed = 7L)
        val queue = CueQueue()
        repeat(500) { queue.emit(Cue(swoosh, Tick(1L), NetId.NONE)) }
        audio.drain(queue)
        val pitches = device.plays.map { it.pitch }
        assertTrue(pitches.all { it in 0.75F..1.25F }, "0.25 variance means 0.75x to 1.25x")
        assertTrue(pitches.min() < 0.85F && pitches.max() > 1.15F, "the whole range is used")

        val fixed = device.plays.filter { it.sound.slot == 0 }
        assertTrue(fixed.isEmpty() || fixed.all { it.pitch == 1F }, "zero variance plays as recorded")
    }

    /** Several files behind one cue means "pick one", which is the point of authoring several. */
    @Test
    fun `a cue with two files plays both over enough hits`() {
        val device = RecordingDevice()
        val audio = CueAudio(device, bindings(device), voiceCap = Int.MAX_VALUE, seed = 3L)
        val queue = CueQueue()
        repeat(200) { queue.emit(Cue(swoosh, Tick(1L), NetId.NONE)) }
        audio.drain(queue)
        assertEquals(2, device.plays.map { it.sound.slot }.distinct().size)
    }
}
