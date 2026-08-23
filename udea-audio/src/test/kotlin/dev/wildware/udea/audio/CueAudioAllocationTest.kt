package dev.wildware.udea.audio

import com.sun.management.ThreadMXBean
import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueId
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The drain path allocates nothing, which is what makes it safe on the frame path in Headless.
 *
 * ## What is measured and what is deliberately not
 *
 * The block measured drains an already-populated queue. Filling it is *not* inside the
 * measurement, because `Cue` is a `data class` and the simulation allocates one per emit - that
 * cost is upstream of this module and counting it here would make the budget a measurement of
 * `udea-core`. What is measured is everything this module contributes: the hoisted consumer, the
 * binding lookup, the position array, the voice ledger, the pitch draw and the device call.
 *
 * A silent device is used because that is the arrangement the budget is a claim about - a headless
 * process, where the mixer runs every frame for the whole session and must cost nothing.
 */
class CueAudioAllocationTest {

    @Test
    fun `draining a full queue allocates nothing`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        val counter = bean?.takeIf { it.isThreadAllocatedMemorySupported }
        if (counter == null) {
            // A JVM with no HotSpot allocation counters cannot answer this. Skipping loudly beats
            // asserting `true` and calling the budget covered.
            println("[udea-audio] thread allocation counters unavailable; budget not measured")
            return
        }
        if (!counter.isThreadAllocatedMemoryEnabled) counter.isThreadAllocatedMemoryEnabled = true

        val cue = CueId(2)
        val bindings = AudioBindings.of(
            listOf(CueSound(cue, "melee_hit", intArrayOf(0, 1, 2), volume = 1F, pitchVariance = 0.5F)),
        )
        val audio = CueAudio(
            AudioDevice.Silent,
            bindings,
            locator = CueSourceLocator.Unlocated,
            voiceCap = Int.MAX_VALUE,
            seed = 11L,
        )
        val queue = CueQueue()
        val batch = 256
        val prepared = Array(batch) { Cue(cue, Tick(it.toLong()), NetId.NONE) }

        fun refill() {
            var index = 0
            while (index < batch) {
                queue.emit(prepared[index])
                index++
            }
        }

        // Warm up so the measurement runs against JIT-compiled code: interpreted Kotlin boxes
        // things compiled Kotlin does not.
        repeat(8) {
            refill()
            audio.drain(queue)
        }

        var smallest = Long.MAX_VALUE
        repeat(5) {
            refill()
            val before = counter.currentThreadAllocatedBytes
            audio.drain(queue)
            val after = counter.currentThreadAllocatedBytes
            smallest = minOf(smallest, after - before)
        }

        assertTrue(
            smallest == 0L,
            "draining $batch cues allocated $smallest bytes; the frame path must be allocation-free",
        )
    }
}
