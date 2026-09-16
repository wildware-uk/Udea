package dev.wildware.udea.core

import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cues cross from a deterministic simulation to a non-deterministic presentation, one way.
 *
 * Two properties matter and both are policy decisions rather than incidental behaviour: the
 * queue is **bounded**, because a headless run has nobody draining it and must not grow a heap
 * of explosions nobody will ever draw; and a full queue drops the **newest**, because the
 * oldest cues are the ones whose effects are already half-played.
 */
class CueQueueTest {

    private fun cue(id: Int, tick: Long = 0L) = Cue(CueId(id), Tick(tick), NetId.NONE)

    @Test
    fun `cues drain in emission order and the queue is then empty`() {
        val queue = CueQueue()
        repeat(5) { queue.emit(cue(it, tick = it.toLong())) }

        val drained = ArrayList<Int>()
        assertEquals(5, queue.drain { drained += it.id.raw })

        assertEquals(listOf(0, 1, 2, 3, 4), drained, "presentation replays them in the order they happened")
        assertEquals(0, queue.size)
        assertEquals(0, queue.drain { error("nothing left to drain") })
    }

    @Test
    fun `a queue nobody drains drops the newest cues rather than growing`() {
        val queue = CueQueue(capacity = 3)

        repeat(10) { queue.emit(cue(it)) }

        assertEquals(3, queue.size, "the ceiling holds")
        assertEquals(7L, queue.droppedCount, "and the overflow is counted, not silent")
        assertEquals(10L, queue.emittedCount)

        val kept = ArrayList<Int>()
        queue.drain { kept += it.id.raw }
        assertEquals(
            listOf(0, 1, 2),
            kept,
            "the oldest cues are kept: their effects are the ones already half-played",
        )
    }

    @Test
    fun `draining makes room again`() {
        val queue = CueQueue(capacity = 2)
        queue.emit(cue(1))
        queue.emit(cue(2))
        queue.emit(cue(3))
        assertEquals(1L, queue.droppedCount)

        queue.drain { }
        queue.emit(cue(4))

        assertEquals(1, queue.size)
        assertEquals(1L, queue.droppedCount, "the drop counter is a history, not a gauge")
    }

    @Test
    fun `clear discards everything without touching the counters`() {
        val queue = CueQueue()
        repeat(4) { queue.emit(cue(it)) }

        queue.clear()

        assertEquals(0, queue.size)
        assertEquals(4L, queue.emittedCount, "how many were emitted is still true after a teardown")
    }

    @Test
    fun `a queue with no capacity is refused at construction`() {
        val failure = assertFailsWith<IllegalArgumentException> { CueQueue(capacity = 0) }
        assertTrue("positive" in failure.message.orEmpty(), "${failure.message}")
    }
}
