package dev.wildware.udea.replay

import dev.wildware.udea.core.Tick

/**
 * Where a seek landed and what it cost, so a caller can assert exactness without polling.
 *
 * Both ticks, because `tickAfter - tickBefore` is what actually ran and a caller given only
 * `tickAfter` would have to have remembered `tickBefore` from a read that may have raced. That
 * is the same defect `TimeToolset.step` was written around, recorded at `DebugBridge.kt:40`: a
 * harness that waited on render frames made `step(n)` approximate, and every determinism
 * measurement built on it silently compared runs of different lengths.
 */
public data class SeekOutcome(
    /** The tick the session was at before the seek. */
    public val tickBefore: Tick,
    /** The tick it is at now. */
    public val tickAfter: Tick,
    /** Simulation ticks actually run. Never negative: a backwards seek rebuilds and runs forwards. */
    public val ticksStepped: Int,
    /** True when the seek had to throw the world away and rebuild it from the first tick. */
    public val rebuilt: Boolean,
    /** The recording's hash at [tickAfter], or `0` when [tickAfter] is the recording's first tick. */
    public val recordedHash: Long,
    /** The hash the replayed world has at [tickAfter]. */
    public val replayedHash: Long,
    /** The first tick this session has *observed* a hash mismatch on, or `null`. */
    public val firstDivergentTick: Tick?,
) {

    /** True when the replayed world agrees with the recording at [tickAfter]. */
    public val matchesRecording: Boolean get() = recordedHash == replayedHash

    override fun toString(): String =
        "SeekOutcome($tickBefore -> $tickAfter, $ticksStepped tick(s)" +
            (if (rebuilt) ", rebuilt" else "") +
            ", ${if (matchesRecording) "matching" else "DIVERGED"})"
}

/**
 * A loaded recording with a live world in it: load, seek, single-step, rewind.
 *
 * ## The workflow this whole engine exists for
 *
 * Issue #149 states it: an agent bisects a reported bug by replaying to the divergence and
 * stepping through it. That is four operations and no more - land on a tick, look, go back, go
 * forward one - and every one of them has to be *exact*, because a bisect over an approximate
 * seek converges on the wrong tick and then reports it with confidence.
 *
 * ## Backwards is a rebuild, and it says so
 *
 * A simulation cannot be run in reverse. [seek] backwards therefore throws the world away,
 * builds a fresh one at the recording's first tick, and fast-forwards - which is exactly what
 * `TimeControl.rewind` does when the snapshot ring has no keyframe near enough, and is the only
 * honest answer. [SeekOutcome.rebuilt] reports it rather than hiding it, because a rebuild is
 * O(distance from the start) and an agent bisecting a late tick backwards one step at a time
 * would otherwise wonder why its calls got slower.
 *
 * This is deliberately *not* wired to the snapshot ring. A ring holds the last few hundred ticks
 * of one running process; a session's job is to be able to land on any tick of a recording that
 * may be an hour long and may have been made last week on another machine. Rebuilding is slower
 * and always correct, and correctness is the property a bisect cannot trade.
 *
 * ## Divergence is observed as it goes
 *
 * Every step compares the world's hash against the recording's for that tick, so a session that
 * has been stepped past a divergence knows where it was without a second pass.
 * [firstDivergentTick] is monotonic in the sense that matters: it holds the earliest tick this
 * session has ever seen disagree, and a rebuild does not clear it, because the fact was learned
 * and throwing it away would make a bisect forget its own result.
 */
public class ReplaySession private constructor(
    /** The loaded recording. */
    public val recording: ReplayRecording,
    private val factory: ReplayWorldFactory,
) : AutoCloseable {

    private var live: ReplayWorld = factory.create(recording.firstTick)
    private val slots: Array<InputSample> = recording.newSampleSlots()

    /** How many times this session has rebuilt its world. A cost an agent may want to see. */
    public var rebuilds: Int = 0
        private set

    /** Simulation ticks this session has run in total, rebuilds included. */
    public var ticksRun: Long = 0L
        private set

    /** The earliest tick this session has seen the replay disagree with the recording on. */
    public var firstDivergentTick: Tick? = null
        private set

    /** The tick the world is at. Equal to `recording.firstTick` immediately after a load. */
    public val tick: Tick get() = live.tick

    /** The live world, for a caller that wants to look at it rather than only at hashes. */
    public val world: ReplayWorld get() = live

    init {
        check(live.tick == recording.firstTick) {
            "the replay world was built at ${live.tick} and the recording starts at " +
                "${recording.firstTick}"
        }
    }

    /**
     * Advances exactly [ticks] ticks of the recording.
     *
     * @throws IllegalArgumentException past the end of the recording. Not clamped: an agent that
     *   asked to step 500 ticks and silently got 30 would read the resulting world as tick
     *   `now+500` and every conclusion after that is wrong.
     */
    public fun step(ticks: Int = 1): SeekOutcome {
        require(ticks >= 0) {
            "replay.step got ticks=$ticks; stepping backwards is rewind, which rebuilds the " +
                "world rather than running the simulation in reverse"
        }
        return seek(tick + ticks.toLong())
    }

    /** Goes back [ticks] ticks. A rebuild; see the class KDoc. */
    public fun rewind(ticks: Int): SeekOutcome {
        require(ticks >= 0) { "replay.rewind got ticks=$ticks; a rewind distance is how far back" }
        return seek(tick - ticks.toLong())
    }

    /**
     * Lands the world exactly on [target].
     *
     * @throws IllegalArgumentException when [target] is outside `[firstTick, endTick]`. The end
     *   is inclusive: the tick one past the last recorded one is the state the recording ends
     *   in, and refusing to land on it would make the final tick of every match unreachable.
     */
    public fun seek(target: Tick): SeekOutcome {
        val first = recording.firstTick
        val end = recording.endTick
        require(target.value >= first.value && target.value <= end.value) {
            "$target is outside this recording, which can be sought anywhere from $first to $end " +
                "inclusive"
        }
        val before = tick
        var rebuilt = false
        if (target.value < before.value) {
            live.close()
            live = factory.create(first)
            rebuilds++
            rebuilt = true
            check(live.tick == first) {
                "a rebuilt replay world came up at ${live.tick} rather than $first"
            }
        }
        var stepped = 0
        while (live.tick.value < target.value) {
            val current = live.tick
            recording.samplesInto(current, slots)
            live.applyInput(slots)
            live.step()
            stepped++
            ticksRun++
            observe(current)
        }
        return SeekOutcome(
            tickBefore = before,
            tickAfter = live.tick,
            ticksStepped = stepped,
            rebuilt = rebuilt,
            recordedHash = hashOfRecordingAt(live.tick),
            replayedHash = live.hash(),
            firstDivergentTick = firstDivergentTick,
        )
    }

    /**
     * The recording's hash for the tick that has just been *completed*.
     *
     * The recording indexes hashes by the tick that produced them, and after running tick `t`
     * the world is at `t + 1`, so landing on tick `t + 1` is compared against the hash recorded
     * for `t`. At the very first tick nothing has run, so there is nothing to compare and the
     * answer is the replayed hash itself - reporting a mismatch there would flag every fresh
     * session as diverged before it had simulated anything.
     */
    private fun hashOfRecordingAt(landed: Tick): Long =
        if (landed.value <= recording.firstTick.value) live.hash()
        else recording.hashAt(landed - 1L)

    private fun observe(ranTick: Tick) {
        if (firstDivergentTick != null) return
        if (live.hash() != recording.hashAt(ranTick)) firstDivergentTick = ranTick
    }

    override fun close() {
        live.close()
    }

    override fun toString(): String =
        "ReplaySession(${recording.header.gameId}, at $tick of ${recording.endTick}, " +
            "rebuilds=$rebuilds" +
            (firstDivergentTick?.let { ", diverged at $it" } ?: ", no divergence seen") + ")"

    public companion object {

        /**
         * Loads [recording] and refuses it here, at load, if this build cannot replay it.
         *
         * At load and not at first step: an agent that opened a recording made against different
         * assets should learn that from the `load` call it made, with the field named, rather
         * than from a divergence report two tools later that points at a fight which was never
         * the same fight.
         *
         * @throws ReplayRefusedException naming every differing identity field.
         */
        public fun load(
            recording: ReplayRecording,
            factory: ReplayWorldFactory,
            identity: BuildIdentity? = null,
        ): ReplaySession {
            if (identity != null) ReplayVerifier.refuseIfMismatched(recording, identity)
            return ReplaySession(recording, factory)
        }
    }
}
