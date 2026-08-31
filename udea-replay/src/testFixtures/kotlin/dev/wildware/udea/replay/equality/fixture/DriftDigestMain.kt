package dev.wildware.udea.replay.equality.fixture

import dev.wildware.udea.core.Tick
import dev.wildware.udea.replay.ReplayVerifier
import dev.wildware.udea.replay.equality.ReplayDigestRecorder
import dev.wildware.udea.replay.equality.ReplayEqualityPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * One matrix leg's half of the `replay-equality` job: replay the fixture, write the digest.
 *
 * ```
 * DriftDigestMain --workspace /home/runner/work/Udea/Udea \
 *                 --out digests/ubuntu-latest-temurin.udeaeq \
 *                 --label ubuntu-latest/temurin-17 [--plant-ulp-at 1200]
 * ```
 *
 * Every leg runs this identical command with a different `--label`, and the join step compares
 * whatever they produced. `--plant-ulp-at` is the deliberate divergence the gate is proven
 * against; nothing in a plain CI run passes it, and `ReplayEqualityProofTest`, the
 * `udeaReplayEqualityProof` task and the workflow's `replay_plant_ulp_at` dispatch input are what
 * do.
 *
 * `--workspace` is not decoration and it is why [parse] exists as a function of its own: issue
 * #169 is the whole of what happens when the base a relative `--out` resolves against is
 * inherited rather than stated. See [ReplayEqualityPaths].
 *
 * @see ReplayDigestRecorder for why this does not compare against the recording's own hashes.
 */
public object DriftDigestMain {

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
        /** Where the leg's wall time goes, or `null`. Always absolute when present. */
        public val timing: Path?,
        /** The tick to plant a one-ulp divergence at, or `null` for an honest leg. */
        public val plantAt: Tick?,
    )

    /**
     * Reads one leg's command line and resolves its paths, without touching the world or the disk.
     *
     * Separate from [main] so that the resolution CI depends on can be asserted against the
     * workflow's own argument strings rather than inferred from them - `ReplayEqualityProofTest`
     * hands this function exactly the `-Pudea.replay.out` value `ci.yml` passes and checks the
     * answer against the directory `actions/upload-artifact` globs.
     */
    public fun parse(args: Array<String>): Options {
        var out: String? = null
        var label: String? = null
        var timing: String? = null
        var workspace: Path = ReplayEqualityPaths.defaultWorkspace()
        var plantAt: Tick? = null
        var at = 0
        while (at < args.size) {
            when (val arg = args[at]) {
                "--out" -> {
                    require(at + 1 < args.size) { "--out needs a path after it" }
                    out = args[at + 1]
                    at++
                }

                "--label" -> {
                    require(at + 1 < args.size) { "--label needs a name after it" }
                    label = args[at + 1]
                    at++
                }

                "--timing" -> {
                    require(at + 1 < args.size) { "--timing needs a path after it" }
                    timing = args[at + 1]
                    at++
                }

                ReplayEqualityPaths.WORKSPACE_OPTION -> {
                    require(at + 1 < args.size) { "${ReplayEqualityPaths.WORKSPACE_OPTION} needs a directory after it" }
                    workspace = Path.of(args[at + 1]).toAbsolutePath().normalize()
                    at++
                }

                "--plant-ulp-at" -> {
                    require(at + 1 < args.size) { "--plant-ulp-at needs a tick after it" }
                    plantAt = Tick(args[at + 1].toLong())
                    at++
                }

                else -> throw IllegalArgumentException("unknown option '$arg'")
            }
            at++
        }

        val requestedOut = requireNotNull(out) { "--out is required" }
        return Options(
            requestedOut = requestedOut,
            workspace = workspace,
            out = ReplayEqualityPaths.resolve(workspace, requestedOut),
            label = requireNotNull(label) {
                "--label is required: it is what a divergence calls this leg"
            },
            timing = timing?.let { ReplayEqualityPaths.resolve(workspace, it) },
            plantAt = plantAt,
        )
    }

    @JvmStatic
    public fun main(args: Array<String>) {
        val options = parse(args)
        val output = options.out

        val recording = DriftFixtureRecorder.readCheckedIn()
        // Refused here rather than at the first differing tick. A recording made against a
        // different input schema presses a different action with every value in range and every
        // array the right length, so nothing downstream would notice.
        ReplayVerifier.refuseIfMismatched(recording, DriftFixtureRecorder.identity())

        val run = ReplayDigestRecorder.record(
            recording = recording,
            factory = DriftWorld.worlds(plantUlpAt = options.plantAt),
            registry = DriftComponents.registry(),
            output = output,
            label = options.label,
            fixture = DriftFixture.PR_FIXTURE,
        )

        // The post-condition, before anything downstream is allowed to assume it. A leg that
        // wrote nothing has to say so here, where it knows the path, rather than leave the
        // upload step two lines later to report a glob that matched nothing.
        val size = ReplayEqualityPaths.requireStreamWritten(options.requestedOut, options.workspace, output)

        val summary = buildString {
            append(options.label).append(": ").append(run.describe())
            if (options.plantAt != null) {
                append("\n  PLANTED: ").append(DriftFixture.PLANT_DESCRIPTION).append(", at ")
                    .append(options.plantAt)
            }
            // The absolute path, not the argument. The argument is what issue #169's legs printed
            // and it is the one thing that did not tell anybody where the bytes went.
            append("\n  ").append(size).append(" bytes at ").append(output)
        }
        println(summary)
        val timing = options.timing
        if (timing != null) {
            Files.createDirectories(timing.parent)
            Files.writeString(timing, "${run.elapsedMillis}\n")
        }
    }
}
