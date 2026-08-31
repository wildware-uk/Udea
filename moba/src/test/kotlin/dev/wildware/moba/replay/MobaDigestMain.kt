package dev.wildware.moba.replay

import dev.wildware.moba.Position
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.ReplayWorld
import dev.wildware.udea.replay.ReplayWorldFactory
import dev.wildware.udea.replay.equality.ReplayDigestCli

/**
 * One `replay-equality` matrix leg's half of the job: replay a `moba` recording, write the digest.
 *
 * ```
 * MobaDigestMain --workspace /home/runner/work/Udea/Udea \
 *                --out digests/ubuntu-latest-temurin.udeaeq \
 *                --label ubuntu-latest/temurin-17 [--fixture moba-36000.udearep]
 *                [--plant-ulp-at 1200]
 * ```
 *
 * Every leg of both jobs runs this identical command with a different `--label`, and the join step
 * compares whatever they produced. `--fixture` chooses which checked-in recording: the gate leaves
 * it alone and takes the 3600-tick one, and the nightly names the 36000-tick one.
 *
 * ## Why it is six lines
 *
 * Because everything a command line does - the options, the path resolution issue #169 is about,
 * the identity refusal, the post-condition that a stream really got written - lives in
 * [ReplayDigestCli], where a test can drive it with `ci.yml`'s own argument strings. What is
 * genuinely this game's is named here and nowhere else: its fixtures, its world, its component
 * registry, and what a planted one-ulp divergence perturbs.
 *
 * `--plant-ulp-at` is the deliberate divergence the gate is proven against. Nothing in an ordinary
 * CI run passes it; the workflow's `replay_plant_ulp_at` dispatch input reaches exactly one leg
 * with it, and `MobaReplayEqualityTest` and `:moba:udeaReplayEqualityProof` are what exercise it
 * on one machine.
 */
public object MobaDigestMain {

    /**
     * The project this entry point belongs to, recorded in every digest header it writes.
     *
     * It is what the join step's reproduce block names, so a reader of a red summary is sent to
     * `:moba:udeaReplayDigest` rather than to `:udea-replay:udeaReplayDigest`, which cannot
     * resolve a fixture name this game owns and exits non-zero saying so.
     */
    public const val GRADLE_PROJECT: String = ":moba"

    /** Reads one leg's command line against this game's fixtures. See [ReplayDigestCli.parse]. */
    public fun parse(args: Array<String>): ReplayDigestCli.Options =
        ReplayDigestCli.parse(args, MobaFixtureKind.entries)

    /**
     * A factory that boots a fresh headless `moba`, optionally planting at [plantUlpAt].
     *
     * The plant wraps the world rather than living inside it, so nothing in `moba`'s shipped
     * source has a branch for it - see [PlantedMobaWorld].
     */
    public fun worlds(plantUlpAt: Tick? = null): ReplayWorldFactory {
        if (plantUlpAt == null) return MobaReplay.worlds()
        return ReplayWorldFactory { firstTick ->
            PlantedMobaWorld(MobaReplay.replayWorld(firstTick), plantUlpAt)
        }
    }

    @JvmStatic
    public fun main(args: Array<String>) {
        val options = parse(args)
        ReplayDigestCli.run(
            options = options,
            recording = MobaFixtureRecorder.readCheckedIn(options.fixture),
            identity = MobaFixtureRecorder.identity(),
            worlds = ::worlds,
            registry = MobaReplay.REGISTRY,
            gradleProject = GRADLE_PROJECT,
            plantDescription = MobaFixture.PLANT_DESCRIPTION,
        )
    }
}

/**
 * A `moba` replay with one ulp added to the champion's `Position.x`, once, at one tick.
 *
 * ## A decorator, and that is the design rather than a convenience
 *
 * `DriftWorld` carries its plant as a constructor parameter because it *is* a fixture. `moba` is
 * the shipped game, and a `plantUlpAt` on [MobaReplayWorld] would put a branch whose only purpose
 * is to corrupt a simulation inside the class every real replay runs through. Wrapping it keeps
 * `src/main` free of the concept entirely: the plant exists in the test source set, which is
 * where the only two callers of it also live.
 *
 * ## After the step, not inside a system
 *
 * So it is plainly a plant and cannot be mistaken for the game's own arithmetic by somebody
 * reading `Player` or `UnitBattleSystems`. `Math.nextUp` is one representable step - the same
 * magnitude `determinism-audit.md` §3.1 measured `Math.sin` differing from `StrictMath.sin` by -
 * and is deliberately far too small to see in a rendering of the number without the raw bits
 * beside it. What makes it show up at all is that `moba` is a chaotic system: a champion one ulp
 * to the left picks a different target a few hundred ticks later, and the whole match follows.
 *
 * @param plantAt the tick to nudge on, read **before** the step for the reason
 *   [MobaReplayWorld.step] gives: `SimClock.tick` names the tick about to be simulated and has
 *   already advanced by the time the step returns, so a plant keyed off the post-step clock lands
 *   one tick earlier than the number somebody typed.
 */
public class PlantedMobaWorld(
    private val delegate: MobaReplayWorld,
    private val plantAt: Tick,
) : ReplayWorld {

    override val tick: Tick get() = delegate.tick

    override fun applyInput(samples: Array<InputSample>) {
        delegate.applyInput(samples)
    }

    override fun step() {
        val simulated = delegate.tick
        delegate.step()
        if (simulated == plantAt) plant()
    }

    override fun hash(): Long = delegate.hash()

    override fun snapshot(): WorldSnapshot? = delegate.snapshot()

    override fun close() {
        delegate.close()
    }

    override fun toString(): String = "PlantedMobaWorld($delegate, plantAt=$plantAt)"

    /**
     * Adds one ulp to the champion's `Position.x`.
     *
     * Refused loudly when there is no champion. A match restart tears the world down and
     * repopulates it inside one barrier action, so there is a window in which the level has no
     * `Player` in it - and a plant that silently did nothing during that window would produce a
     * leg that agrees with every other leg, on a run whose entire purpose was to watch the gate
     * fail. That is the exact shape of green that proves the opposite of what it claims.
     */
    private fun plant() {
        val host = delegate.host
        val netId = checkNotNull(MobaEntry.playerIdOrNull(host)) {
            "there is no champion in the world at $plantAt, so a one-ulp plant on Position.x " +
                "would write nothing and the planted leg would agree with the honest ones. Pick " +
                "a tick at which the level has a Player in it."
        }
        val entity = checkNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(netId)) {
            "$netId is not live at $plantAt"
        }
        with(host.world) {
            val position = entity[Position]
            position.x = Math.nextUp(position.x)
        }
    }
}
