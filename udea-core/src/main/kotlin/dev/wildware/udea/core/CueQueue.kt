package dev.wildware.udea.core

/**
 * The [CueSink] a simulation writes to and presentation drains.
 *
 * Cues are one-shot presentation events — a hit landed, an ability fired — that the simulation
 * emits and never reads back. Holding them in a queue rather than calling a renderer directly
 * is what keeps presentation code out of `world.update` (spec 3.3), and it is why a headless
 * simulation can emit cues that nothing ever collects without changing what it simulates.
 *
 * ## Not simulation state
 *
 * A cue never enters a snapshot. That makes [clear] correct rather than lossy at a scene swap:
 * the queue may hold cues emitted by a world that no longer exists, and playing an explosion
 * from the previous level after a scene change is the bug, not the fix.
 *
 * ## Bounded
 *
 * [capacity] cues per drain. A presentation layer that stops draining — a hidden window, a
 * headless run with no renderer — must not grow this without limit, so emits past the ceiling
 * are dropped and counted in [droppedCount] instead of being retained. Dropping the *newest*
 * keeps the oldest cues, which are the ones whose effects have already been half-played.
 *
 * Not thread-safe: the simulation writes on the sim thread and presentation drains between
 * ticks, on the same thread, like the rest of the kernel.
 */
public class CueQueue(
    /** How many undrained cues may be held. Beyond this, emits are dropped and counted. */
    public val capacity: Int = DEFAULT_CAPACITY,
) : CueSink {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val pending = ArrayList<Cue>(minOf(capacity, DEFAULT_CAPACITY))

    /** Cues emitted and not yet drained. */
    public val size: Int get() = pending.size

    /** Cues discarded because the queue was full. Non-zero means nobody is draining. */
    public var droppedCount: Long = 0L
        private set

    /** Cues emitted since construction, dropped ones included. */
    public var emittedCount: Long = 0L
        private set

    override fun emit(cue: Cue) {
        emittedCount++
        if (pending.size >= capacity) {
            droppedCount++
            return
        }
        pending += cue
    }

    /**
     * Hands every pending cue to [consume], in emission order, and empties the queue.
     *
     * @return how many cues were drained.
     */
    public fun drain(consume: (Cue) -> Unit): Int {
        val drained = pending.size
        var index = 0
        while (index < drained) {
            consume(pending[index])
            index++
        }
        pending.clear()
        return drained
    }

    /** Discards every pending cue. What a scene teardown does. */
    public fun clear() {
        pending.clear()
    }

    override fun toString(): String = "CueQueue(size=${pending.size}/$capacity, dropped=$droppedCount)"

    public companion object {
        /**
         * A busy tick's cues for a 5v5 fight, with room for a few ticks of backlog, and small
         * enough that a headless run that never drains costs a few kilobytes rather than a heap.
         */
        public const val DEFAULT_CAPACITY: Int = 1024
    }
}
