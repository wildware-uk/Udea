package dev.wildware.udea.replay.equality.fixture

import dev.wildware.udea.replay.equality.ReplayDigestCli

/**
 * The drift world's half of a `replay-equality` leg: replay the fixture, write the digest.
 *
 * ```
 * DriftDigestMain --workspace /home/runner/work/Udea/Udea \
 *                 --out digests/ubuntu-latest-temurin.udeaeq \
 *                 --label ubuntu-latest/temurin-17 [--fixture drift-36000.udearep]
 *                 [--plant-ulp-at 1200]
 * ```
 *
 * ## What this is now, and what it is not
 *
 * It is the gate's **self-test**, and since issue #172 that is all it is. The CI legs replay
 * `moba` - a world that was not written to be deterministic, which is the whole of #172's
 * argument - and this world, which routes its trigonometry through `StrictMath` because its
 * author knew exactly which call was the trap, is what proves the *machinery* can fail:
 * `udeaReplayEqualityProof` plants into it across five processes, and
 * `CrossPlatformDivergenceTest` renders its divergence against a checked-in expected output.
 *
 * Every option, every path resolution and the identity refusal live in [ReplayDigestCli], which
 * `moba`'s entry point runs too. Nothing about a command line is duplicated between the two
 * games, so nothing about a command line can differ between them.
 *
 * `--workspace` is why the parsing is a function of its own rather than inline: issue #169 is the
 * whole of what happens when the base a relative `--out` resolves against is inherited rather
 * than stated.
 *
 * @see dev.wildware.udea.replay.equality.ReplayDigestRecorder for why this does not compare
 *   against the recording's own hashes.
 */
public object DriftDigestMain {

    /**
     * The project this entry point belongs to, recorded in every digest header it writes.
     *
     * It is what the join step's reproduce block names, so a reader of a red summary is sent to a
     * `udeaReplayDigest` that can actually resolve the fixture that diverged.
     */
    public const val GRADLE_PROJECT: String = ":udea-replay"

    /** Reads one leg's command line against this world's fixtures. See [ReplayDigestCli.parse]. */
    public fun parse(args: Array<String>): ReplayDigestCli.Options =
        ReplayDigestCli.parse(args, DriftFixtureKind.entries)

    @JvmStatic
    public fun main(args: Array<String>) {
        val options = parse(args)
        ReplayDigestCli.run(
            options = options,
            recording = DriftFixtureRecorder.readCheckedIn(options.fixture),
            identity = DriftFixtureRecorder.identity(),
            worlds = { plantAt -> DriftWorld.worlds(plantUlpAt = plantAt) },
            registry = DriftComponents.registry(),
            gradleProject = GRADLE_PROJECT,
            plantDescription = DriftFixture.PLANT_DESCRIPTION,
        )
    }
}
