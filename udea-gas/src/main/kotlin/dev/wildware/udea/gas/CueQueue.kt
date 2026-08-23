package dev.wildware.udea.gas

import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueId
import dev.wildware.udea.core.CueSink
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId

/**
 * One gameplay cue, as flat values.
 *
 * ## What it replaces
 *
 * `GameplayEffectCue.onGameplayEffectApplied(source, target, spec)`, called inline from
 * `Abilities.applyGameplayEffect` (`common/.../Abilities.kt:61-67`), whose implementations
 * reached for `world.system<SoundSystem>()` and `AnimationSetSystem` (`example/.../DamageCue.kt:26-31`).
 * Applying an effect therefore touched audio and animation — in `RenderMode.Headless` too, which
 * spec 3.5 forbids — and rewinding through an application played the sound a second time.
 *
 * ## Why every field is a primitive
 *
 * A cue that held an `AssetReference` or a component would pin state the simulation may have
 * moved on from: a `GraphDelta` hot reload can swap the underlying asset between the tick that
 * emitted the cue and the frame that draws it (spec 3.6). Ids and [NetId]s stay meaningful across
 * both. Instances are pooled inside [GasCueQueue] and mutated in place, so emitting one costs no
 * allocation — read the fields during a drain, never keep the object.
 */
public class CueEvent internal constructor() {

    /** Which presentation effect to play. Dense, from the generated cue registry. */
    public var cueId: Int = 0
        internal set

    /** Who caused it. */
    public var source: NetId = NetId.NONE
        internal set

    /** Who it happened to. */
    public var target: NetId = NetId.NONE
        internal set

    /** The application this cue belongs to, for de-duplication against a predicted cue. */
    public var effectHandle: EffectHandle = EffectHandle.INVALID
        internal set

    /** The tick the simulation emitted it on. */
    public var tick: Tick = Tick.ZERO
        internal set

    /** First payload float — damage dealt, healing done, knockback strength. */
    public var payload0: Float = 0f
        internal set

    /** Second payload float. */
    public var payload1: Float = 0f
        internal set

    /** The prediction key the client played this under, or [NO_PREDICTION]. */
    public var predictionKey: Int = NO_PREDICTION
        internal set

    override fun toString(): String =
        "CueEvent(id=$cueId, source=$source, target=$target, $effectHandle, $tick)"

    public companion object {
        /** No client prediction was involved. */
        public const val NO_PREDICTION: Int = 0
    }
}

/**
 * Whether emitted cues are kept or dropped.
 *
 * Suppression is what makes rollback re-simulation and the agent's `fast_forward` usable at all:
 * without it, rewinding sixty seconds and re-simulating replays sixty seconds of sound. It is a
 * mode on the queue rather than a check at every call site so that no simulation code has to know
 * whether it is being replayed — which is the property that keeps a re-simulation bit-identical.
 */
public enum class CueMode {
    /** Normal simulation: cues are kept for presentation to drain. */
    Emit,

    /** Rollback re-simulation and fast-forward: cues are counted and dropped. */
    Suppress,
}

/**
 * The per-world outbound cue queue: simulation appends, presentation drains once per frame.
 *
 * The outbound counterpart to `SimBarrier` (spec 3.3). Simulation never reads it back, which is
 * what lets [clear] be correct rather than lossy and what makes a headless run that never drains
 * cost a bounded few kilobytes.
 *
 * ## Never snapshot state
 *
 * A cue is not simulation state and never enters a snapshot: capture ignores it and restore
 * leaves it untouched, so a world hash is identical whether the queue held a hundred pending cues
 * or none. [rewind] empties it, because cues emitted by ticks that are about to be re-simulated
 * would otherwise play twice.
 *
 * ## De-duplication
 *
 * A client that predicted an ability has already played its cue; the server's confirmation must
 * not play it again. [emit] drops a cue whose `(cueId, effectHandle, predictionKey)` matches one
 * of the last [DEDUP_WINDOW] emitted. A fixed window rather than a set because it must not
 * allocate and must not grow without bound — and because a duplicate that arrives later than the
 * window is a duplicate nobody would have noticed anyway.
 */
public class GasCueQueue(
    /** How many undrained cues may be held. Beyond it, emits are dropped and counted. */
    public val capacity: Int = DEFAULT_CAPACITY,
    /**
     * Where a core [Cue] is also forwarded, for a presentation layer that only knows the kernel's
     * cue type. Optional: `null` means this queue is the only consumer.
     */
    private val sink: CueSink? = null,
) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    /** Preallocated event objects, reused in place. Emitting allocates nothing. */
    private val events: Array<CueEvent> = Array(capacity) { CueEvent() }

    private val dedupKeys = LongArray(DEDUP_WINDOW)
    private var dedupNext = 0
    private var dedupFilled = 0

    /** Whether emitted cues are kept. Set to [CueMode.Suppress] around a re-simulation. */
    public var mode: CueMode = CueMode.Emit

    /** How many cues are pending a drain. */
    public var size: Int = 0
        private set

    /** Cues dropped because [mode] was [CueMode.Suppress]. */
    public var suppressedCount: Long = 0L
        private set

    /** Cues dropped because the queue was full. Non-zero means nobody is draining. */
    public var droppedCount: Long = 0L
        private set

    /** Cues dropped as duplicates of an already-played predicted cue. */
    public var deduplicatedCount: Long = 0L
        private set

    /**
     * Emits a cue, unless suppressed, duplicated or over capacity.
     *
     * @return true when the cue was kept.
     */
    public fun emit(
        cueId: Int,
        tick: Tick,
        source: NetId = NetId.NONE,
        target: NetId = NetId.NONE,
        effectHandle: EffectHandle = EffectHandle.INVALID,
        payload0: Float = 0f,
        payload1: Float = 0f,
        predictionKey: Int = CueEvent.NO_PREDICTION,
    ): Boolean {
        if (mode == CueMode.Suppress) {
            suppressedCount++
            return false
        }
        val key = dedupKey(cueId, effectHandle, predictionKey)
        if (isDuplicate(key)) {
            deduplicatedCount++
            return false
        }
        rememberKey(key)
        if (size == capacity) {
            droppedCount++
            return false
        }
        val event = events[size]
        event.cueId = cueId
        event.tick = tick
        event.source = source
        event.target = target
        event.effectHandle = effectHandle
        event.payload0 = payload0
        event.payload1 = payload1
        event.predictionKey = predictionKey
        size++
        sink?.emit(Cue(CueId(cueId), tick, source))
        return true
    }

    /** The pending cue at [index], `0 until` [size]. Valid only until the next drain. */
    public fun eventAt(index: Int): CueEvent {
        require(index in 0 until size) { "no pending cue at $index; $size pending" }
        return events[index]
    }

    /** Hands every pending cue to [consume] in emission order and empties the queue. */
    public fun drain(consume: (CueEvent) -> Unit): Int {
        val drained = size
        var index = 0
        while (index < drained) {
            consume(events[index])
            index++
        }
        size = 0
        return drained
    }

    /** Discards pending cues. What a scene teardown does. */
    public fun clear() {
        size = 0
    }

    /**
     * Discards pending cues and the de-duplication window before a rewind re-simulates.
     *
     * The window goes too: the re-simulation will legitimately re-emit the same
     * `(cueId, handle)` pairs, and after a rewind those are new cues rather than duplicates.
     */
    public fun rewind() {
        size = 0
        dedupNext = 0
        dedupFilled = 0
    }

    /** Runs [block] with cues suppressed, restoring the previous mode afterwards. */
    public inline fun <T> suppressed(block: () -> T): T {
        val previous = mode
        mode = CueMode.Suppress
        try {
            return block()
        } finally {
            mode = previous
        }
    }

    private fun dedupKey(cueId: Int, handle: EffectHandle, predictionKey: Int): Long =
        (cueId.toLong() shl 40) xor (handle.raw.toLong() shl 8) xor predictionKey.toLong()

    private fun isDuplicate(key: Long): Boolean {
        var index = 0
        while (index < dedupFilled) {
            if (dedupKeys[index] == key) return true
            index++
        }
        return false
    }

    private fun rememberKey(key: Long) {
        dedupKeys[dedupNext] = key
        dedupNext = (dedupNext + 1) % DEDUP_WINDOW
        if (dedupFilled < DEDUP_WINDOW) dedupFilled++
    }

    override fun toString(): String = "GasCueQueue($size/$capacity, mode=$mode)"

    public companion object {
        /** A busy tick's cues for a 5v5 fight, with a few ticks of backlog. */
        public const val DEFAULT_CAPACITY: Int = 256

        /**
         * How many recent cues a duplicate is checked against.
         *
         * Sixty-four: a client predicts a handful of abilities at a time and the server's
         * confirmation follows within a round trip, so the window covers well over the span a
         * duplicate can arrive in, while staying a scan short enough to stay off the profile.
         */
        public const val DEDUP_WINDOW: Int = 64
    }
}
