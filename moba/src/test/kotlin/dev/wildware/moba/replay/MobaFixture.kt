package dev.wildware.moba.replay

import dev.wildware.udea.core.Tick
import dev.wildware.udea.replay.equality.ReplayDigestCli
import dev.wildware.udea.replay.equality.ReplayFixtureRef

/**
 * The checked-in `moba` recordings the cross-OS `replay-equality` gate replays, and their numbers.
 *
 * ## Why the gate replays this game and not a fixture world
 *
 * Issue #172, and its argument is one sentence long: `DriftWorld` routes its trigonometry through
 * `StrictMath` because its author knew exactly which call was the trap, so a gate pointed at it
 * reports the health of its own fixture. `moba` was not written that way. It has twenty-seven AI
 * units, Box2D-free overlap resolution, a lane of creeps, towers, projectiles, a shop, an ability
 * system and a match loop, and **nothing in this repository had ever replayed it on two operating
 * systems and compared it field by field**. The drift world stays checked in as the *self-test* -
 * `udeaReplayEqualityProof` plants into it across five processes and `CrossPlatformDivergenceTest`
 * pins its rendered failure - and the CI legs replay these.
 *
 * ## Both lengths, and why the long one is not a different question
 *
 * The gate's short recording runs on every push. The nightly's is ten times longer, and it is
 * where a drift too small to move the world hash inside a minute has a chance to surface: an
 * accumulated float, a `Math.sin` table entry that is only reached from one heading, a hash
 * iteration order that only matters once a collection has grown. Aiming the more sensitive gate
 * at the less interesting world would have been the wrong half to move.
 */
public object MobaFixture {

    /**
     * The pilot's seed, for `java.util.Random`.
     *
     * A specified LCG on purpose: `java.util.Random`'s *algorithm* is written into its
     * specification, so the same seed rebuilds the same input stream on any conforming JVM and
     * the checked-in bytes are regenerable by anyone. It never enters the simulation - it authors
     * a recording, offline - and simulation randomness stays `RngService` and its named streams.
     */
    public const val PILOT_SEED: Long = 0x0BA_5EED_172L

    /** The gate's length: one minute of simulated play at the 60Hz `AGENTS.md` fixes. */
    public const val PR_TICKS: Int = 3600

    /** The name the gate's fixture is checked in under, and the name a digest header carries. */
    public const val PR_FIXTURE: String = "moba-3600.udearep"

    /** The classpath resource the gate's fixture is read from. */
    public const val PR_RESOURCE: String = "/fixtures/moba-3600.udearep"

    /**
     * The nightly's length: ten times the gate's, in the gate's own unit.
     *
     * A tick count and not a duration. At 60Hz it is ten minutes of play, but a number of seconds
     * written down here is a number that stops being true the day somebody changes the rate, and
     * every deadline, ring slot and baseline in this tree is a `Tick` for that reason.
     */
    public const val NIGHTLY_TICKS: Int = 36_000

    /** The name the nightly's fixture is checked in under. */
    public const val NIGHTLY_FIXTURE: String = "moba-36000.udearep"

    /** The classpath resource the nightly's fixture is read from. */
    public const val NIGHTLY_RESOURCE: String = "/fixtures/moba-36000.udearep"

    /**
     * The tick `replay_plant_ulp_at` and the local proof plant a divergence at.
     *
     * A third of the way into the gate's fixture, so a report has the full five ticks of history
     * to print and the run has plenty of matching ticks behind it - a divergence at tick one would
     * pass every assertion while proving nothing about a stream that has to stay in step for a
     * minute.
     */
    public val PLANT_TICK: Tick = Tick(1_200L)

    /** Why the plant is one ulp, in one sentence, for a report that has to justify itself. */
    public const val PLANT_DESCRIPTION: String =
        "one ulp on Position.x of the champion, which is the magnitude determinism-audit.md " +
            "section 3.1 measured Math.sin differing from StrictMath.sin by"

    /** The Gradle task a reader is sent to when a fixture of this game has gone stale. */
    public const val GRADLE_TASK: String = ":moba:test"

    /** The source directory the bytes are checked in under, relative to `moba/`. */
    public const val FIXTURES_DIR: String = "src/test/resources/fixtures"
}

/**
 * The checked-in recordings of this game, as the two CI jobs that replay them name them.
 *
 * A list rather than a count, so adding a third is an addition here and nothing else has to be
 * corrected. `--fixture` on [MobaDigestMain] resolves against it, so a `ci.yml` typo names the
 * fixtures that do exist instead of failing somewhere inside a classpath lookup.
 *
 * The first entry is what a leg naming no fixture replays, which is the gate's.
 */
public enum class MobaFixtureKind(
    /** The file's name, as it is checked in and as a digest header carries it. */
    override val fixtureName: String,
    /** Where a replay reads it from, on the classpath. */
    override val resource: String,
    /** How long it is. */
    override val ticks: Int,
) : ReplayFixtureRef {

    /** The one every push replays: `replay-equality`, and the one the plant lands in. */
    PR(MobaFixture.PR_FIXTURE, MobaFixture.PR_RESOURCE, MobaFixture.PR_TICKS),

    /** The one the nightly replays: ten times as long, and never on a pull request. */
    NIGHTLY(MobaFixture.NIGHTLY_FIXTURE, MobaFixture.NIGHTLY_RESOURCE, MobaFixture.NIGHTLY_TICKS),
    ;

    public companion object {

        /** The kind called [name], or a failure that lists the ones there are. */
        public fun byName(name: String): MobaFixtureKind =
            ReplayDigestCli.fixtureNamed(entries, name)
    }
}
