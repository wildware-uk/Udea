package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.replay.BuildIdentity
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.ReplayVerifier
import dev.wildware.udea.replay.ReplayWorldFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * One checked-in recording a `replay-equality` leg can be pointed at.
 *
 * An interface rather than a shared enum because the *set* of fixtures belongs to the game that
 * records them: `moba` has its own two and the drift world has its own two, and neither can name
 * the other's without an arrow through the module table that must not exist. What is shared is
 * the three things a leg's command line has to know about a fixture, and they are here.
 */
public interface ReplayFixtureRef {

    /** The file's name, as a digest header carries it and as `-Pudea.replay.fixture` spells it. */
    public val fixtureName: String

    /** Where a replay reads it from, on the classpath. */
    public val resource: String

    /** How long it is. */
    public val ticks: Int
}

/**
 * The command line one `replay-equality` matrix leg runs, and the run behind it.
 *
 * ## Why this is not `DriftDigestMain`'s private business any more
 *
 * Issue #172 points the gate at `moba`, and a game's digest entry point has to live in that
 * game: `udea-replay` is above `moba` in the module table and may not name `MobaReplay`. The
 * option parser, the path resolution, the identity refusal and the post-condition are the same
 * on both sides and none of them is game-shaped, so copying them into `moba` would be exactly
 * the "copy-pasted logic that differs only in a constant" `docs/engineering-standards.md` §8
 * rejects - and, worse, two parsers that could disagree about what `--out` means, which is the
 * whole of issue #169 with a second place to go wrong.
 *
 * So a game's `main` is the six lines that name its own world, its own registry and its own
 * fixtures, and everything a CI job depends on is here, where `ReplayEqualityProofTest` can
 * drive it with the workflow's own argument strings.
 */
public object ReplayDigestCli {

    /** One leg's command line, with every path already resolved. */
    public class Options(
        /** The raw `--out`, kept so a failure can name what was asked for as well as what it meant. */
        public val requestedOut: String,
        /** The base a relative path in this command line was resolved against. */
        public val workspace: Path,
        /** Where this leg's digest stream goes. Always absolute. */
        public val out: Path,
        /** What a divergence calls this leg. */
        public val label: String,
        /** Which checked-in recording this leg replays. */
        public val fixture: ReplayFixtureRef,
        /** Where the leg's wall time goes, or `null`. Always absolute when present. */
        public val timing: Path?,
        /** The tick to plant a one-ulp divergence at, or `null` for an honest leg. */
        public val plantAt: Tick?,
    )

    /**
     * Reads one leg's command line and resolves its paths, without touching the world or the disk.
     *
     * Separate from any `main` so that the resolution CI depends on can be asserted against the
     * workflow's own argument strings rather than inferred from them - the ci.yml tests hand this
     * function exactly the `-Pudea.replay.out` value the job passes and check the answer against
     * the directory `actions/upload-artifact` globs.
     *
     * @param known every fixture the calling game has. `--fixture` resolves against it, so a
     *   `ci.yml` typo names the fixtures that do exist instead of failing inside a classpath
     *   lookup. The first entry is what a leg that names none replays.
     */
    public fun parse(args: Array<String>, known: List<ReplayFixtureRef>): Options {
        require(known.isNotEmpty()) { "a game with no checked-in fixtures cannot run a digest leg" }
        var out: String? = null
        var label: String? = null
        var timing: String? = null
        var fixture: ReplayFixtureRef = known.first()
        var workspace: Path = ReplayEqualityPaths.defaultWorkspace()
        var plantAt: Tick? = null
        var at = 0
        while (at < args.size) {
            when (val arg = args[at]) {
                OUT_OPTION -> {
                    require(at + 1 < args.size) { "$OUT_OPTION needs a path after it" }
                    out = args[at + 1]
                    at++
                }

                LABEL_OPTION -> {
                    require(at + 1 < args.size) { "$LABEL_OPTION needs a name after it" }
                    label = args[at + 1]
                    at++
                }

                TIMING_OPTION -> {
                    require(at + 1 < args.size) { "$TIMING_OPTION needs a path after it" }
                    timing = args[at + 1]
                    at++
                }

                FIXTURE_OPTION -> {
                    require(at + 1 < args.size) { "$FIXTURE_OPTION needs a fixture name after it" }
                    fixture = fixtureNamed(known, args[at + 1])
                    at++
                }

                ReplayEqualityPaths.WORKSPACE_OPTION -> {
                    require(at + 1 < args.size) {
                        "${ReplayEqualityPaths.WORKSPACE_OPTION} needs a directory after it"
                    }
                    workspace = Path.of(args[at + 1]).toAbsolutePath().normalize()
                    at++
                }

                PLANT_OPTION -> {
                    require(at + 1 < args.size) { "$PLANT_OPTION needs a tick after it" }
                    plantAt = Tick(args[at + 1].toLong())
                    at++
                }

                else -> throw IllegalArgumentException("unknown option '$arg'")
            }
            at++
        }

        val requestedOut = requireNotNull(out) { "$OUT_OPTION is required" }
        return Options(
            requestedOut = requestedOut,
            workspace = workspace,
            out = ReplayEqualityPaths.resolve(workspace, requestedOut),
            label = requireNotNull(label) {
                "$LABEL_OPTION is required: it is what a divergence calls this leg"
            },
            fixture = fixture,
            timing = timing?.let { ReplayEqualityPaths.resolve(workspace, it) },
            plantAt = plantAt,
        )
    }

    /** The fixture in [known] called [name], or a failure that lists the ones there are. */
    public fun <T : ReplayFixtureRef> fixtureNamed(known: List<T>, name: String): T =
        known.firstOrNull { it.fixtureName == name }
            ?: throw IllegalArgumentException(
                "no fixture is called '$name'; this world has " +
                    known.joinToString(", ") { it.fixtureName },
            )

    /**
     * Replays [recording] into a world from [worlds], writes the digest, and reports.
     *
     * @param identity what this build would refuse the recording over. Refused here rather than
     *   at the first differing tick: a recording made against a different input schema presses a
     *   different action with every value in range and every array the right length, so nothing
     *   downstream would notice.
     * @param worlds a factory-of-factories, taking the tick to plant a one-ulp divergence at.
     *   Every game's plant is a different field of a different component, so the perturbation is
     *   the game's and only the tick is this function's.
     * @param gradleProject the Gradle project this entry point belongs to, e.g. `:moba`. It goes
     *   into the digest header so the join step's reproduce block names the module that can
     *   actually replay this fixture - see [ReplayDigestHeader.gradleProject].
     * @param plantDescription what [worlds] does when it is handed a tick, in one sentence, for a
     *   report that has to justify itself.
     */
    public fun run(
        options: Options,
        recording: ReplayRecording,
        identity: BuildIdentity,
        worlds: (Tick?) -> ReplayWorldFactory,
        registry: ComponentRegistry,
        gradleProject: String,
        plantDescription: String,
    ): ReplayDigestRun {
        ReplayVerifier.refuseIfMismatched(recording, identity)

        val run = ReplayDigestRecorder.record(
            recording = recording,
            factory = worlds(options.plantAt),
            registry = registry,
            output = options.out,
            label = options.label,
            fixture = options.fixture.fixtureName,
            gradleProject = gradleProject,
        )

        // The post-condition, before anything downstream is allowed to assume it. A leg that
        // wrote nothing has to say so here, where it knows the path, rather than leave the
        // upload step two lines later to report a glob that matched nothing.
        val size = ReplayEqualityPaths.requireStreamWritten(
            options.requestedOut,
            options.workspace,
            options.out,
        )

        println(
            buildString {
                append(options.label).append(": ").append(run.describe())
                if (options.plantAt != null) {
                    append("\n  PLANTED: ").append(plantDescription).append(", at ")
                        .append(options.plantAt)
                }
                // The absolute path, not the argument. The argument is what issue #169's legs
                // printed and it is the one thing that did not say where the bytes went.
                append("\n  ").append(size).append(" bytes at ").append(options.out)
            },
        )

        val timing = options.timing
        if (timing != null) {
            Files.createDirectories(timing.parent)
            Files.writeString(timing, "${run.elapsedMillis}\n")
        }
        return run
    }

    /** Where this leg's digest stream goes. */
    public const val OUT_OPTION: String = "--out"

    /** What a divergence calls this leg. */
    public const val LABEL_OPTION: String = "--label"

    /** Where the leg's measured wall time goes. */
    public const val TIMING_OPTION: String = "--timing"

    /** Which checked-in recording to replay. */
    public const val FIXTURE_OPTION: String = "--fixture"

    /** The tick to plant a one-ulp divergence at. */
    public const val PLANT_OPTION: String = "--plant-ulp-at"
}
