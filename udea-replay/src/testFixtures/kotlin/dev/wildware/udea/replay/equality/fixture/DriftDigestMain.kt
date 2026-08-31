package dev.wildware.udea.replay.equality.fixture

import dev.wildware.udea.core.Tick
import dev.wildware.udea.replay.ReplayVerifier
import dev.wildware.udea.replay.equality.ReplayDigestRecorder
import java.nio.file.Files
import java.nio.file.Path

/**
 * One matrix leg's half of the `replay-equality` job: replay the fixture, write the digest.
 *
 * ```
 * DriftDigestMain --out build/replay-equality/ubuntu-latest-temurin.udeaeq \
 *                 --label ubuntu-latest/temurin-17 [--plant-ulp-at 1200]
 * ```
 *
 * Every leg runs this identical command with a different `--label`, and the join step compares
 * whatever they produced. `--plant-ulp-at` is the deliberate divergence the gate is proven
 * against; nothing in CI passes it, and `ReplayEqualityProofTest` and the `udeaReplayEqualityProof`
 * task are what do.
 *
 * @see ReplayDigestRecorder for why this does not compare against the recording's own hashes.
 */
public object DriftDigestMain {

    @JvmStatic
    public fun main(args: Array<String>) {
        var out: Path? = null
        var label: String? = null
        var timing: Path? = null
        var plantAt: Tick? = null
        var at = 0
        while (at < args.size) {
            when (val arg = args[at]) {
                "--out" -> {
                    require(at + 1 < args.size) { "--out needs a path after it" }
                    out = Path.of(args[at + 1])
                    at++
                }

                "--label" -> {
                    require(at + 1 < args.size) { "--label needs a name after it" }
                    label = args[at + 1]
                    at++
                }

                "--timing" -> {
                    require(at + 1 < args.size) { "--timing needs a path after it" }
                    timing = Path.of(args[at + 1])
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

        val output = requireNotNull(out) { "--out is required" }
        val name = requireNotNull(label) { "--label is required: it is what a divergence calls this leg" }

        val recording = DriftFixtureRecorder.readCheckedIn()
        // Refused here rather than at the first differing tick. A recording made against a
        // different input schema presses a different action with every value in range and every
        // array the right length, so nothing downstream would notice.
        ReplayVerifier.refuseIfMismatched(recording, DriftFixtureRecorder.identity())

        val run = ReplayDigestRecorder.record(
            recording = recording,
            factory = DriftWorld.worlds(plantUlpAt = plantAt),
            registry = DriftComponents.registry(),
            output = output,
            label = name,
            fixture = DriftFixture.PR_FIXTURE,
        )

        val summary = buildString {
            append(name).append(": ").append(run.describe())
            if (plantAt != null) {
                append("\n  PLANTED: ").append(DriftFixture.PLANT_DESCRIPTION).append(", at ")
                    .append(plantAt)
            }
            append("\n  ").append(Files.size(output)).append(" bytes on disk")
        }
        println(summary)
        if (timing != null) {
            Files.createDirectories(timing.toAbsolutePath().parent)
            Files.writeString(timing, "${run.elapsedMillis}\n")
        }
    }
}
