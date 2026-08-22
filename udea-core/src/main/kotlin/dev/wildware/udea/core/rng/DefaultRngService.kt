package dev.wildware.udea.core.rng

import dev.wildware.udea.core.RngService
import dev.wildware.udea.core.RngStream

/**
 * [RngService] over one [SimRandom] per named stream (spec 5, "Randomness").
 *
 * ## Why streams are named
 *
 * With one generator, every draw is positional: the third combat roll of the match is the
 * third value of the sequence. Add a loot roll in Phase 5 and it consumes a value, so every
 * combat roll after it shifts, every checked-in replay fixture breaks, and the diff says the
 * combat code changed when it did not. Named streams make a draw depend only on *its own*
 * stream's history, so adding a consumer is a local change. `RngStreamIsolationTest` asserts
 * exactly that property, because it is the entire reason this class exists.
 *
 * ## Why stream seeds are derived, not stored
 *
 * Each stream's seed is `splitMix64(rootSeed + GOLDEN_GAMMA * (ordinal + 1))`, and the four
 * state words come from a SplitMix64 run over that. Two consequences, both load-bearing:
 *
 * - a stream's seed depends on nothing but the root seed and its own ordinal, so **appending
 *   a stream to the enum cannot perturb an existing one** — pinned by the checked-in seed
 *   values in `RngStreamIsolationTest`, which cover ordinals the enum does not have yet;
 * - the extra mix before the run means two adjacent ordinals do not share a SplitMix64
 *   window. Seeding stream *n* straight from `rootSeed + n * GAMMA` would give stream 1 the
 *   state words stream 0 was about to use, and the two would run three-quarters correlated.
 *
 * Ordinals, not names, so a stream inserted *between* two existing ones reseeds everything
 * after it. New streams go on the end.
 *
 * ## Snapshotting
 *
 * [saveState] and [restore] move the whole service through a flat `LongArray` of
 * [stateWords] entries — no per-stream objects, no map, nothing to allocate — which is what
 * the snapshot ring writes and reads directly.
 */
public class DefaultRngService(
    /** The root seed every stream is derived from. */
    public val rootSeed: Long,
) : RngService {

    override val seed: Long get() = rootSeed

    /**
     * One generator per stream, indexed by ordinal and created once. A `stream()` call is an
     * array read, so a system may draw in its hot loop without allocating.
     */
    private val streams: Array<SimRandom> =
        Array(STREAMS.size) { ordinal -> SimRandom(streamSeed(rootSeed, ordinal)) }

    /** Longs a full [saveState] occupies. Fixed by the enum; asserted by the layout test. */
    public val stateWords: Int get() = streams.size * SimRandom.STATE_WORDS

    /**
     * The generator backing [id]. The same instance every time, for the life of the service.
     */
    public fun stream(id: RngStream): SimRandom = streams[id.ordinal]

    override fun nextInt(stream: RngStream, bound: Int): Int = streams[stream.ordinal].nextInt(bound)

    override fun nextFloat(stream: RngStream): Float = streams[stream.ordinal].nextFloat()

    override fun nextLong(stream: RngStream): Long = streams[stream.ordinal].nextLong()

    override fun nextBoolean(stream: RngStream): Boolean = streams[stream.ordinal].nextBoolean()

    /**
     * Every stream's state, stream-ordinal-major, four longs each.
     *
     * Layout is `[stream0.s0, stream0.s1, stream0.s2, stream0.s3, stream1.s0, ...]` and is
     * part of the snapshot format: changing it invalidates every recorded snapshot.
     */
    public fun saveState(): LongArray {
        val out = LongArray(stateWords)
        saveInto(out, 0)
        return out
    }

    /** [saveState] without the allocation, for a capture that already owns a buffer. */
    public fun saveInto(into: LongArray, offset: Int = 0) {
        requireRoom(into.size, offset, "save")
        var at = offset
        for (index in streams.indices) {
            streams[index].save(into, at)
            at += SimRandom.STATE_WORDS
        }
    }

    /**
     * Restores every stream from a [saveState] array.
     *
     * @throws IllegalArgumentException if [state] is not exactly [stateWords] long. A short
     *   array means the snapshot predates a stream being added, and silently leaving that
     *   stream at its current state would produce a restore that diverges from the capture —
     *   the one failure this whole contract exists to make impossible.
     */
    public fun restore(state: LongArray) {
        require(state.size == stateWords) {
            "RngService state must be exactly $stateWords longs " +
                "(${streams.size} streams x ${SimRandom.STATE_WORDS} words), was ${state.size}"
        }
        restoreFrom(state, 0)
    }

    /** [restore] from a region of a larger snapshot buffer. */
    public fun restoreFrom(state: LongArray, offset: Int = 0) {
        requireRoom(state.size, offset, "restore")
        var at = offset
        for (index in streams.indices) {
            streams[index].load(state, at)
            at += SimRandom.STATE_WORDS
        }
    }

    private fun requireRoom(size: Int, offset: Int, what: String) {
        require(offset >= 0 && size - offset >= stateWords) {
            "cannot $what $stateWords RNG state words at offset $offset of a LongArray($size)"
        }
    }

    override fun toString(): String = "DefaultRngService(rootSeed=$rootSeed, streams=${streams.size})"

    public companion object {

        private val STREAMS: List<RngStream> = RngStream.entries

        /**
         * The seed for the stream at [ordinal], a pure function of it and [rootSeed].
         *
         * Exposed so `RngStreamIsolationTest` can pin its output for ordinals past the end of
         * today's enum. A test cannot append an enum constant, and asserting `streamSeed`
         * against itself proves nothing, because it would move on both sides; checked-in
         * seed values are what actually turn red if a future seeding scheme reads the stream
         * population instead of only the ordinal.
         */
        public fun streamSeed(rootSeed: Long, ordinal: Int): Long {
            require(ordinal >= 0) { "stream ordinal must not be negative, was $ordinal" }
            return SimRandom.splitMix64(rootSeed + SimRandom.GOLDEN_GAMMA * (ordinal + 1L))
        }
    }
}
