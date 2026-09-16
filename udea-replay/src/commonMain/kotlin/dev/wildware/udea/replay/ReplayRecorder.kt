package dev.wildware.udea.replay

import dev.wildware.udea.core.Tick

/**
 * Append-only, one tick at a time, in the order the server ran them.
 *
 * ## Append-only is checked, not described
 *
 * [record] refuses any tick that is not exactly one past the previous one. That single `require`
 * is what makes the recording an ordered log rather than a map that happened to be filled in
 * order: a producer that skipped a tick, replayed a tick, or recorded two peers' ticks out of
 * step fails on the tick it did it, with both numbers in the message, instead of producing a
 * file that replays into a divergence three thousand ticks later.
 *
 * It is the property `InputRing` deliberately does not have. That ring overwrites its oldest
 * entry the moment it is full, because a prediction buffer wants the last two seconds and
 * nothing else. Here, forgetting the opening minute would make the recording unreplayable, since
 * a replay must start where the recorder started.
 *
 * ## Sealed once, then immutable
 *
 * Frames accumulate into a [ByteSink] as they arrive - so recording costs one `u8` per idle
 * peer-tick and no allocation once the sink is warm - and [seal] wraps them, with the frame
 * index and the hash stream, into a [ReplayRecording]. Sealing is what fixes `tickCount`, which
 * is why the header is written at seal time rather than at construction: a recorder cannot know
 * how long a match will be, and a header written up front would have to be patched afterwards by
 * a writer that had already streamed past it.
 *
 * @param identityWithoutSchema the seed, the protocol hash and the asset graph hash. The input
 *   schema hash is not a parameter: it is taken from [schema], so the two cannot disagree.
 */
public class ReplayRecorder(
    identityWithoutSchema: BuildIdentity,
    /** The vocabulary every recorded sample is written in. */
    public val schema: InputSchema,
    /** How many peers each tick carries a sample for. */
    public val peerCount: Int,
    /** The game writing this, for a human reading `replay.info`. */
    public val gameId: String,
    /** That game's version string. */
    public val gameVersion: String,
    /** The simulation's fixed rate. */
    public val tickRateHz: Int = DEFAULT_TICK_RATE_HZ,
) {

    init {
        require(peerCount in 1..ReplayFormat.MAX_PEERS) {
            "a recording carries 1..${ReplayFormat.MAX_PEERS} peers, got $peerCount"
        }
        require(tickRateHz > 0) { "a tick rate is positive, was $tickRateHz" }
    }

    /** The four identity fields, with [schema]'s hash substituted in so they cannot drift. */
    public val identity: BuildIdentity =
        identityWithoutSchema.copy(inputSchemaHash = schema.hash)

    private val frames = ByteSink()
    private var offsets = IntArray(INITIAL_TICKS + 1)
    private var hashes = LongArray(INITIAL_TICKS)

    /** How many ticks have been recorded. */
    public var tickCount: Int = 0
        private set

    /** The first recorded tick, or `null` before [record] has been called once. */
    public var firstTick: Tick? = null
        private set

    /** True once [seal] has run. A sealed recorder refuses further ticks. */
    public var sealed: Boolean = false
        private set

    /** The tick [record] will accept next, or `null` before the first one, which may be any tick. */
    public val nextTick: Tick? get() = firstTick?.let { it + tickCount.toLong() }

    /**
     * Appends one tick: every peer's input, then the world hash that tick produced.
     *
     * [worldHash] is `WorldHasher.hash(snapshot)` over a capture taken **after** the tick ran.
     * After, because a hash taken before it would describe the previous tick's world and the
     * whole stream would be off by one - a verifier would then report the divergence one tick
     * late, which for a bisect is the difference between landing on the cause and landing on its
     * first consequence.
     *
     * @throws IllegalStateException if [tick] is not [nextTick], or if this recorder is sealed.
     */
    public fun record(tick: Tick, samples: Array<InputSample>, worldHash: Long) {
        check(!sealed) { "this recorder was sealed at ${nextTick ?: firstTick}; it is append-only, and sealing ends the appending" }
        require(samples.size == peerCount) {
            "this recording has $peerCount peer(s) and was handed ${samples.size} sample(s)"
        }
        val expected = nextTick
        if (expected == null) {
            firstTick = tick
        } else {
            check(tick == expected) {
                "a recording is append-only and contiguous: the next tick is $expected and " +
                    "$tick was offered. A producer that skips or repeats a tick writes a file " +
                    "that replays into a divergence with no cause in it"
            }
        }
        check(tickCount < ReplayFormat.MAX_TICKS) {
            "a recording may carry at most ${ReplayFormat.MAX_TICKS} ticks and this one is full"
        }
        growIfNeeded()
        offsets[tickCount] = frames.size
        for (peer in 0 until peerCount) {
            require(samples[peer].schema == schema) {
                "sample $peer is over ${samples[peer].schema}, not this recorder's $schema"
            }
            samples[peer].writeTo(frames)
        }
        hashes[tickCount] = worldHash
        tickCount++
    }

    /**
     * Seals the log and hands back the recording.
     *
     * @throws IllegalStateException on an empty recorder. A zero-tick recording is never what
     *   anyone meant - it means the producer never ran - and letting one be written would make
     *   "the replay matched" trivially true.
     */
    public fun seal(): ReplayRecording {
        check(!sealed) { "this recorder has already been sealed" }
        val start = firstTick
        checkNotNull(start) {
            "nothing was recorded, so there is no match to seal. A zero-tick recording would " +
                "replay identically to anything at all"
        }
        sealed = true
        val index = offsets.copyOf(tickCount + 1)
        index[tickCount] = frames.size
        return ReplayRecording(
            header = ReplayHeader(
                identity = identity,
                schema = schema,
                tickRateHz = tickRateHz,
                firstTick = start,
                tickCount = tickCount,
                peerCount = peerCount,
                gameId = gameId,
                gameVersion = gameVersion,
            ),
            frameBytes = frames.toByteArray(),
            frameOffsets = index,
            hashes = hashes.copyOf(tickCount),
        )
    }

    /** Fresh sample slots, one per peer, over this recorder's schema. Allocate once, reuse. */
    public fun newSampleSlots(): Array<InputSample> = Array(peerCount) { InputSample(schema) }

    override fun toString(): String =
        "ReplayRecorder($gameId, $tickCount tick(s) from ${firstTick ?: "<nothing yet>"}, " +
            "${frames.size} frame byte(s)${if (sealed) ", sealed" else ""})"

    private fun growIfNeeded() {
        if (tickCount + 1 < offsets.size) return
        val capacity = offsets.size + (offsets.size shr 1)
        offsets = offsets.copyOf(capacity)
        hashes = hashes.copyOf(capacity)
    }

    public companion object {

        /** The fixed rate spec section 5 pins the simulation to. */
        public const val DEFAULT_TICK_RATE_HZ: Int = 60

        /** Ticks the index and hash stream start sized for: ten seconds at 60Hz. */
        public const val INITIAL_TICKS: Int = 600
    }
}
