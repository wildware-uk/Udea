package dev.wildware.udea.gas

/**
 * Identifies one *applied* effect on one entity, for the life of that application.
 *
 * An [EffectHandle] is what an ability holds onto so it can find the cooldown effect it
 * applied, and what makes the modifier sort order total (see `AttributeSystem`). Both uses
 * require it to survive a snapshot restore, which the old one could not: `EffectHandle.next()`
 * read a `private var nextId` on the companion (`common/ability/GameplayEffectSpec.kt:66-68`),
 * a process-wide static. Two worlds in one JVM shared it, and a rewind rolled the world back
 * without rolling the counter back — so an ability's `cooldownHandle` pointed at a handle the
 * restored world had never allocated, and the ability came off cooldown early.
 */
@JvmInline
public value class EffectHandle(public val raw: Int) : Comparable<EffectHandle> {

    /** True for a handle that names no application. */
    public val isInvalid: Boolean get() = raw < 0

    override fun compareTo(other: EffectHandle): Int = raw.compareTo(other.raw)

    override fun toString(): String = if (isInvalid) "EffectHandle.INVALID" else "EffectHandle#$raw"

    public companion object {
        /** The handle that names nothing. What an ability's `cooldownHandle` starts as. */
        public val INVALID: EffectHandle = EffectHandle(-1)
    }
}

/**
 * The per-world source of [EffectHandle]s, whose whole state is one `Int` inside the snapshot.
 *
 * ## Monotonic, and never recycled
 *
 * `release` exists — the recompute loop calls it when an effect expires — but it does **not**
 * hand the id back out. It decrements a live counter, which is what makes a leak detectable
 * ([liveCount] climbing without bound is a bug in a caller, and a test can assert it does not).
 * Recycling would be wrong twice over: the modifier sort key ends in the handle, so reusing a
 * number would let a freshly applied effect sort where an expired one used to and change a
 * recompute's result for reasons nothing gameplay-side asked for; and a stale handle held by an
 * ability would silently resolve to a *different* effect, the same use-after-free that `NetId`
 * carries a generation counter to prevent.
 *
 * At 32 bits and the busiest plausible rate — a 5v5 applying an effect per entity per tick —
 * exhaustion is ~35 days of continuous play, well past any session, and [allocate] fails loudly
 * rather than wrapping into aliasing.
 *
 * ## Captured
 *
 * [next] is the entire state, so capture is [saveInto] into a single-`Int` [HandleAllocatorState]
 * and restore is [restoreFrom]. That is what makes a rewind reproduce the same handle sequence:
 * step to tick 200, rewind to 100, step forward again, and every effect applied on the way gets
 * the number it got the first time.
 */
public class HandleAllocator {

    /** The next handle that will be issued. The whole of this allocator's state. */
    public var next: Int = 0
        private set

    /** Handles issued and not yet released. A leak check, not a correctness invariant. */
    public var liveCount: Int = 0
        private set

    /**
     * Issues the next handle.
     *
     * @throws HandleSpaceExhaustedException rather than wrapping. A wrapped handle aliases a
     *   live effect, and a silent alias is a gameplay bug on the wrong entity.
     */
    public fun allocate(): EffectHandle {
        if (next == Int.MAX_VALUE) throw HandleSpaceExhaustedException(next)
        val handle = EffectHandle(next)
        next++
        liveCount++
        return handle
    }

    /**
     * Records that [handle] is no longer in use. The id is not reissued — see the class KDoc.
     *
     * @throws IllegalArgumentException if [handle] was never issued by this allocator, which
     *   catches an effect list restored from another world's snapshot.
     */
    public fun release(handle: EffectHandle) {
        require(!handle.isInvalid) { "EffectHandle.INVALID cannot be released" }
        require(handle.raw < next) {
            "$handle was never issued by this allocator (next is $next); an effect list from " +
                "another world has been restored into it"
        }
        liveCount--
    }

    /** Copies this allocator's whole state into [state]. What a snapshot capture calls. */
    public fun saveInto(state: HandleAllocatorState) {
        state.next = next
        state.liveCount = liveCount
    }

    /** Restores from [state]. Subsequent handles resume from the restored value. */
    public fun restoreFrom(state: HandleAllocatorState) {
        require(state.next >= 0) { "a restored handle counter must not be negative, was ${state.next}" }
        next = state.next
        liveCount = state.liveCount
    }

    /** Back to a freshly constructed allocator. What a scene teardown calls. */
    public fun reset() {
        next = 0
        liveCount = 0
    }

    override fun toString(): String = "HandleAllocator(next=$next, live=$liveCount)"
}

/**
 * The captured state of a [HandleAllocator].
 *
 * A type of its own rather than a bare `Int` so a snapshot ring slot can hold it beside
 * `HandleState` (the `NetIdIndex`'s equivalent) and so the two cannot be swapped by accident.
 *
 * `udea-core`'s snapshot ring does not know about this type: `WorldSnapshot` carries a
 * `HandleState` for entity ids and nothing for GAS. Wiring it into the ring needs a
 * `udea-core`-side hook that does not exist yet, so today the capture and restore calls are the
 * caller's to make. That is a real gap and it is why `GasSnapshotEquivalenceTest` drives both
 * halves explicitly rather than through `SnapshotService`.
 */
public class HandleAllocatorState {

    /** The allocator's next id. */
    public var next: Int = 0
        internal set

    /** Handles outstanding at capture. */
    public var liveCount: Int = 0
        internal set

    override fun toString(): String = "HandleAllocatorState(next=$next, live=$liveCount)"
}

/** A world that issued 2^31 effect handles. Loud, because the alternative is aliasing. */
public class HandleSpaceExhaustedException(
    public val issued: Int,
) : IllegalStateException(
    "this world has issued $issued effect handles and the space is exhausted; handles are " +
        "never recycled by design, so this is a leak in a caller rather than a limit to raise",
)
