package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick

/**
 * Who produced a digest stream, what they replayed, and what their components were.
 *
 * [label] is what a failure calls the two sides — `ubuntu-latest/temurin-17` against
 * `windows-latest/temurin-17` — and it is the caller's, not this module's: a CI job knows what
 * leg it is and a local run knows it is a local run, and inventing one from the JVM's own
 * properties would produce two legs that call themselves the same thing.
 */
public class ReplayDigestHeader(
    /** The matrix leg, e.g. `ubuntu-latest/temurin-17`. Printed on every side of a divergence. */
    public val label: String,
    /** The recording that was replayed, by name, so a join cannot compare two different fixtures. */
    public val fixture: String,
    /** `ReplayHeader.gameId` of the recording. */
    public val gameId: String,
    /** `ReplayHeader.gameVersion` of the recording. */
    public val gameVersion: String,
    /** The recording's first tick. Cell streams are indexed from here. */
    public val firstTick: Tick,
    /** How many ticks were replayed. */
    public val tickCount: Int,
    /**
     * `java.vm.vendor`, `java.vm.name` and `java.version` of the JVM that produced the stream.
     *
     * Recorded rather than asserted. The two-JVM axis is the point of the exercise
     * (`determinism-audit.md` §3.1: `Math.sin` is permitted a 1-ulp error and is not specified
     * to agree between implementations), so a green join is only interesting if a reader can see
     * that the two sides really were two JVMs — and a *red* one needs to name them.
     */
    public val jvm: String,
    /** `os.name` and `os.arch` of the machine that produced the stream. Recorded, not asserted. */
    public val os: String,
    /**
     * The Gradle project whose `udeaReplayDigest` produced this stream, e.g. `:moba`.
     *
     * Carried because the join step has to tell a reader how to reproduce a divergence and cannot
     * work it out: `ReplayEqualsMain` reads nothing but the files, and since issue #172 two
     * different projects each register a `udeaReplayDigest` over their own fixtures. The block
     * `ReplayBisectGuide` renders used to name `:udea-replay` unconditionally, which after #172
     * printed a command that fails - `no fixture is called 'moba-3600.udearep'` - as the
     * instruction for reproducing a red gate.
     *
     * Deliberately **not** part of [incomparabilitiesAgainst]. It is a function of the game, and
     * the game is already pinned there by [gameId], [gameVersion] and [fixture]; adding a fourth
     * name for the same fact would refuse a comparison for a reason none of the three could.
     */
    public val gradleProject: String,
    /** Every component type the capture registry held, in ascending type id. */
    public val components: List<DigestComponentInfo>,
) {

    private val byTypeId: Map<Int, DigestComponentInfo> =
        components.associateBy { it.typeId.raw }

    /** The component table entry for [typeIdRaw], or `null`. */
    public fun componentOf(typeIdRaw: Int): DigestComponentInfo? = byTypeId[typeIdRaw]

    /**
     * Every reason [other] cannot be compared against this stream, in words.
     *
     * Empty means the two are comparable. Comparing streams whose component tables disagree would
     * be worse than useless: every cell after the first differing type would be matched against a
     * cell that means something else, and the report would name fields that never diverged. The
     * honest answer is to refuse and say which of the two builds is not the one that was meant.
     */
    public fun incomparabilitiesAgainst(other: ReplayDigestHeader): List<String> = buildList {
        if (fixture != other.fixture) {
            add("fixture: '$fixture' against '${other.fixture}'")
        }
        if (gameId != other.gameId) {
            add("game: '$gameId' against '${other.gameId}'")
        }
        if (gameVersion != other.gameVersion) {
            add("game version: '$gameVersion' against '${other.gameVersion}'")
        }
        if (firstTick != other.firstTick) {
            add("first tick: $firstTick against ${other.firstTick}")
        }
        if (tickCount != other.tickCount) {
            add("tick count: $tickCount against ${other.tickCount}")
        }
        addAll(componentDifferences(other))
    }

    private fun componentDifferences(other: ReplayDigestHeader): List<String> = buildList {
        for (mine in components) {
            val theirs = other.componentOf(mine.typeId.raw)
            if (theirs == null) {
                add("component ${mine.componentFqn} (${mine.typeId}) is absent from '${other.label}'")
            } else if (!mine.agreesWith(theirs)) {
                add(
                    "component ${mine.typeId} is ${mine.componentFqn}${mine.fieldNames} here and " +
                        "${theirs.componentFqn}${theirs.fieldNames} in '${other.label}'",
                )
            }
        }
        for (theirs in other.components) {
            if (componentOf(theirs.typeId.raw) == null) {
                add("component ${theirs.componentFqn} (${theirs.typeId}) is absent from '$label'")
            }
        }
    }

    override fun toString(): String =
        "$label [$os, $jvm] $gameId $gameVersion, $fixture, $tickCount ticks from $firstTick"
}
