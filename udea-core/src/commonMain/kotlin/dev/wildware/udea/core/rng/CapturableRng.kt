package dev.wildware.udea.core.rng

import dev.wildware.udea.core.RngService

/**
 * An [RngService] whose whole state fits in a flat `LongArray` a snapshot can carry.
 *
 * Separate from [RngService] because the two say different things. [RngService] is what a
 * *system* may call: draw a number from a named stream, and nothing else. This is what the
 * *snapshot ring* needs: move every stream's state into and out of a buffer the ring already
 * owns. A system that could reach [restoreFrom] could rewind the world's randomness from
 * inside a tick, which is precisely the class of mutation `SimBarrier` exists to prevent.
 *
 * It exists at all because the snapshot-equivalence gate is only meaningful if *every* source
 * of simulation randomness is capturable (spec 7). `SnapshotService` refuses to be built
 * against an [RngService] that does not implement this, loudly and at construction, rather
 * than capturing a world whose random streams would silently diverge on the re-run.
 *
 * ## The buffer, not an array
 *
 * [saveInto] and [restoreFrom] take a caller-owned array and an offset rather than returning
 * one. A capture runs sixty times a second inside a budget that permits zero allocation, and
 * `saveState()`-shaped API hands back a fresh array every time.
 */
public interface CapturableRng {

    /**
     * How many `Long`s [saveInto] writes. Fixed for the life of the service, and part of the
     * snapshot layout: a snapshot recorded with fewer streams than the code now has cannot be
     * restored, which is a loud failure rather than a stream left at the wrong state.
     */
    public val stateWords: Int

    /** Writes every stream's state into [into] at [offset]. Allocation-free. */
    public fun saveInto(into: LongArray, offset: Int = 0)

    /** Reads every stream's state back from [state] at [offset]. */
    public fun restoreFrom(state: LongArray, offset: Int = 0)
}
