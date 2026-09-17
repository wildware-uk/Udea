package dev.wildware.udea.replay

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.snapshot.DivergenceReport
import dev.wildware.udea.core.snapshot.FieldDivergence

/**
 * What a replay proved, or exactly where it stopped proving it.
 *
 * A bare "the hashes matched" is a claim; this is the evidence for it, including the two numbers
 * a reader can compare by eye and the count of ticks that actually ran - because a verification
 * that compared zero ticks would otherwise look identical to one that compared two thousand.
 */
public class ReplayVerification(
    /** The recording that was replayed. */
    public val header: ReplayHeader,
    /** How many ticks were replayed and compared. Equals `header.tickCount` on a clean run. */
    public val ticksCompared: Int,
    /** The first tick whose hash differed, or `null` when the whole stream matched. */
    public val firstDivergentTick: Tick?,
    /** The hash the recording holds at [firstDivergentTick]. Meaningless when there is none. */
    public val recordedHash: Long,
    /** The hash the replay produced there. */
    public val replayedHash: Long,
    /**
     * Every field the two worlds disagree about at [firstDivergentTick], roster order.
     *
     * Empty both when nothing diverged and when no [BaselineSnapshots] could supply the
     * record-time world; [describe] distinguishes the two in words, and [fieldsAvailable]
     * distinguishes them in a boolean a tool can publish.
     */
    public val fields: List<FieldDivergence>,
    /** Whether a baseline world was reconstructed at [firstDivergentTick]. */
    public val fieldsAvailable: Boolean,
) {

    /** True when every recorded tick replayed to the same hash. The claim this exists to make. */
    public val isBitExact: Boolean get() = firstDivergentTick == null

    /** How many ticks matched before the first divergence, or all of them. */
    public val matchingTicks: Int
        get() = firstDivergentTick?.let { (it.value - header.firstTick.value).toInt() }
            ?: ticksCompared

    /**
     * The sentence a test failure, a log line and `replay.verify` all print.
     *
     * Capped at [DivergenceReport.MAX_REPORTED] fields for the reason that class gives: a world
     * that has genuinely diverged usually diverges everywhere, and one screenful of the first
     * few is more useful than ten thousand lines of the rest.
     */
    public fun describe(): String {
        if (isBitExact) {
            return "bit-exact: $ticksCompared tick(s) from ${header.firstTick} replayed to the " +
                "recorded hash stream, every tick"
        }
        val tick = firstDivergentTick
        val builder = StringBuilder()
        builder.append("replay diverged at ").append(tick)
            .append(" (").append(matchingTicks).append(" tick(s) matched first): recorded hash ")
            .append(recordedHash).append(", replayed ").append(replayedHash)
        if (!fieldsAvailable) {
            builder.append("\n  no baseline world was available, so the differing fields cannot ")
                .append("be named: a .udearep stores one hash per tick, not a world per tick. ")
                .append("Supply a BaselineSnapshots - a host with a snapshot ring can rewind to ")
                .append("this tick and capture it - to get the field list.")
            return builder.toString()
        }
        if (fields.isEmpty()) {
            builder.append("\n  no field differs, so the divergence is in the clock, the random ")
                .append("streams or the id allocator; none of those are fields, and all three ")
                .append("are folded into the hash by WorldHasher.hash(WorldSnapshot)")
            return builder.toString()
        }
        builder.append("\n  ").append(fields.size).append(" differing field(s):")
        for (field in fields.take(DivergenceReport.MAX_REPORTED)) {
            builder.append("\n    ").append(field)
        }
        if (fields.size > DivergenceReport.MAX_REPORTED) {
            builder.append("\n    ... and ").append(fields.size - DivergenceReport.MAX_REPORTED)
                .append(" more")
        }
        return builder.toString()
    }

    override fun toString(): String = describe()
}

/**
 * Replays a recording into a fresh world and compares the two hash streams, tick by tick.
 *
 * ## The proof, stated
 *
 * A recording carries the input every peer sent and the world hash each tick produced. A replay
 * takes a world built from the same seed, feeds it the recorded input, and hashes the world it
 * gets. The two streams either agree on every tick - which is bit-exactness, and is what the
 * fixed 60Hz timestep, the seeded named RNG streams, the absence of any wall clock in simulation
 * and the input-only client-to-server vocabulary were all built for - or they do not, in which
 * case the *first* index where they differ is the tick the simulation stopped being a function
 * of its inputs. Every later tick is a consequence.
 *
 * ## Why it stops at the first divergence
 *
 * Because everything after it is noise. Two worlds that have diverged do not re-converge, and a
 * report of "1,847 ticks differ" answers a question nobody asked. `DivergenceReport` takes the
 * same position for the same reason, and this class deliberately produces the same shape - a
 * tick, two hashes, and a field list - so an agent that can read one can read the other.
 */
public object ReplayVerifier {

    /**
     * Replays [recording] into a world from [factory] and compares every tick.
     *
     * @param identity this build's identity. The recording is refused before a single tick runs
     *   if it cannot possibly be valid here - see [BuildIdentity.mismatchesAgainst], which is
     *   the whole of issue #147's second half. Pass `null` only when the caller has already
     *   checked, which `ReplaySession` has.
     * @param baseline where the record-time world at the divergent tick comes from, when
     *   anything can supply one. [BaselineSnapshots.NONE] is the honest default for a file that
     *   arrived from another machine.
     * @throws ReplayRefusedException when [identity] does not match the recording's.
     */
    public fun verify(
        recording: ReplayRecording,
        factory: ReplayWorldFactory,
        identity: BuildIdentity? = null,
        baseline: BaselineSnapshots = BaselineSnapshots.NONE,
    ): ReplayVerification {
        if (identity != null) refuseIfMismatched(recording, identity)

        val world = factory.create(recording.firstTick)
        try {
            check(world.tick == recording.firstTick) {
                "the replay world was built at ${world.tick} and the recording starts at " +
                    "${recording.firstTick}; a replay that starts one tick out diverges on tick " +
                    "one with no cause anywhere in the world"
            }
            val slots = recording.newSampleSlots()
            var compared = 0
            for (index in 0 until recording.tickCount) {
                val tick = recording.firstTick + index.toLong()
                recording.samplesInto(tick, slots)
                world.applyInput(slots)
                world.step()
                compared++
                val replayed = world.hash()
                val recorded = recording.hashAt(tick)
                if (replayed != recorded) {
                    return diverged(recording, world, baseline, tick, recorded, replayed, compared)
                }
            }
            return ReplayVerification(
                header = recording.header,
                ticksCompared = compared,
                firstDivergentTick = null,
                recordedHash = 0L,
                replayedHash = 0L,
                fields = emptyList(),
                fieldsAvailable = false,
            )
        } finally {
            world.close()
        }
    }

    /**
     * Refuses [recording] unless [identity] can replay it, naming every field that differs.
     *
     * Public because `ReplaySession.load` performs the same check at load time rather than at
     * first step: an agent that loaded a recording made against different assets should be told
     * so by the `load` call it made, not by a `seek` two tools later.
     */
    public fun refuseIfMismatched(recording: ReplayRecording, identity: BuildIdentity) {
        val mismatches = recording.header.identity.mismatchesAgainst(identity)
        if (mismatches.isNotEmpty()) {
            throw ReplayRefusedException(mismatches, recording.header)
        }
    }

    private fun diverged(
        recording: ReplayRecording,
        world: ReplayWorld,
        baseline: BaselineSnapshots,
        tick: Tick,
        recorded: Long,
        replayed: Long,
        compared: Int,
    ): ReplayVerification {
        val replaySnapshot = world.snapshot()
        val baselineSnapshot = if (replaySnapshot == null) null else baseline.snapshotAt(tick)
        val fields = if (baselineSnapshot == null) {
            emptyList()
        } else {
            DivergenceReport.compare(tick, baselineSnapshot, replaySnapshot!!).fields
        }
        return ReplayVerification(
            header = recording.header,
            ticksCompared = compared,
            firstDivergentTick = tick,
            recordedHash = recorded,
            replayedHash = replayed,
            fields = fields,
            fieldsAvailable = baselineSnapshot != null,
        )
    }
}
