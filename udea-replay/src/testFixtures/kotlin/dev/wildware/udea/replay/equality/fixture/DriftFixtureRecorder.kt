package dev.wildware.udea.replay.equality.fixture

import dev.wildware.udea.replay.BuildIdentity
import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.ReplayRecorder
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.fixture.ReplayFixture
import dev.wildware.udea.replay.fixture.ReplayFixtures
import java.nio.file.Path
import java.util.Random

/**
 * Writes and reads the checked-in `.udearep` the `replay-equality` job replays.
 *
 * ## The pilot is a specified LCG, on purpose
 *
 * `java.util.Random` is one of the few classes whose *algorithm* is written into its
 * specification - a 48-bit linear congruential generator with named constants - so
 * `Random(seed)` produces the same stream of bits on every conforming JVM. That is what makes
 * [record] regenerable: anyone can rebuild the fixture from this file and get the same input
 * stream, on any machine.
 *
 * It is emphatically **not** a simulation RNG. It never enters a world: it authors a recording,
 * offline, once. Simulation randomness is `RngService` and its named streams, which is what
 * `DriftSystem` and `ChargeSystem` draw from.
 *
 * ## The recording's own hash stream is one machine's answer
 *
 * A `.udearep` carries the world hash per tick, and the hashes in the checked-in file are
 * whatever the machine that generated it produced. That is exactly the number the cross-OS gate
 * is trying to find out about, so nothing in this ticket asserts a replay against it -
 * `ReplayDigestRecorder` records the mismatch count and reports it, and the verdict comes from
 * comparing two legs against each other. A test that gated on the stored hashes would make the
 * fixture's birthplace the authority and blame the other platform for a difference both sides
 * are equally responsible for.
 */
public object DriftFixtureRecorder {

    /** The pilot's seed. Separate from [DriftFixture.SEED]: the input is not the simulation. */
    public const val PILOT_SEED: Long = 0x5EED_D817_F1F0L

    /** How often the pilot presses `pulse`, as one draw in this many. */
    public const val PULSE_ODDS: Int = 24

    /**
     * The pilot's press counter is a `u8`, and it rolls over rather than climbing.
     *
     * `InputSample.setPressCount` takes `0..255` and refuses anything else - a press count is one
     * byte on the wire, and every game that sends one sends a rolling counter that wraps. The
     * fixture pilot ignored that and kept a lifetime total, which fitted only because the
     * 3600-tick fixture presses roughly `3600 / PULSE_ODDS` = 150 times. The 36000-tick fixture
     * of issue #165 presses about ten times as often, and recording it threw
     * `a press count must be in 0..255, was 256 for action 'drift/pulse'` partway through - so
     * the length of every fixture this world can have was capped by a limit nothing named.
     *
     * `ChargeSystem` reads the counter as `pulseCount > lastPulseCount`, so on the tick a wrap
     * lands it sees 0 after 255 and declines to fire. That is one missed press in every 256 and
     * it is arithmetic on integers, so both legs of a cross-OS comparison miss the same one. The
     * fixture's job is to churn deterministically, and it still does.
     */
    public const val PULSE_COUNT_MASK: Int = 0xFF

    /**
     * Records [ticks] of piloted play into a fresh fixture world.
     *
     * @return the sealed recording, ready to be written or replayed.
     */
    public fun record(ticks: Int = DriftFixture.PR_TICKS): ReplayRecording {
        require(ticks > 0) { "a fixture of $ticks tick(s) proves nothing" }
        val world = DriftWorld()
        val recorder = ReplayRecorder(
            identityWithoutSchema = identity(),
            schema = DriftFixture.SCHEMA,
            peerCount = 1,
            gameId = DriftFixture.GAME_ID,
            gameVersion = DriftFixture.GAME_VERSION,
        )
        val pilot = Random(PILOT_SEED)
        val sample = InputSample(DriftFixture.SCHEMA)
        val slots = arrayOf(sample)
        var pulses = 0
        try {
            repeat(ticks) {
                val tick = world.tick
                sample.setAxis(
                    DriftFixture.AXIS_MOVE,
                    pilot.nextFloat() * 2f - 1f,
                    pilot.nextFloat() * 2f - 1f,
                )
                if (pilot.nextInt(PULSE_ODDS) == 0) pulses = (pulses + 1) and PULSE_COUNT_MASK
                sample.setPressed(DriftFixture.ACTION_PULSE, pulses % 2 == 1)
                sample.setPressCount(DriftFixture.ACTION_PULSE, pulses)

                world.applyInput(slots)
                world.step()
                recorder.record(tick, slots, world.hash())
            }
        } finally {
            world.close()
        }
        return recorder.seal()
    }

    /**
     * The four fields a replay of this fixture is refused over.
     *
     * The asset graph hash is the fixture's own name in UTF-8 rather than a real registry: this
     * world loads no assets, and a zero-length hash would make the one identity field that can
     * *only* be wrong by accident impossible to get wrong at all.
     */
    public fun identity(): BuildIdentity = BuildIdentity(
        rootSeed = DriftFixture.SEED,
        protoHash = DrifterReplicator.typeId.raw * PROTO_MIX + ChargeReplicator.typeId.raw,
        assetGraphHash = DriftFixture.GAME_ID.toByteArray(Charsets.UTF_8),
        inputSchemaHash = DriftFixture.SCHEMA.hash,
    )

    /** Reads one checked-in fixture from the classpath. */
    public fun readCheckedIn(kind: DriftFixtureKind = DriftFixtureKind.PR): ReplayRecording {
        val bytes = checkNotNull(javaClass.getResourceAsStream(kind.resource)) {
            "${kind.resource} is not on the classpath. It is checked in under " +
                "udea-replay/src/testFixtures/resources; rebuild it with " +
                ReplayFixtures.updateCommand(GRADLE_TASK)
        }.use { it.readBytes() }
        return ReplayRecording.decode(bytes)
    }

    /**
     * Every fixture of this world, as `--update-replay-fixtures` reconciles them.
     *
     * @param fixturesDir the source directory the bytes are checked in under, normally
     *   `udea-replay/src/testFixtures/resources/fixtures`. A parameter rather than a constant
     *   because the caller knows where the source tree is and this class does not - it is loaded
     *   from a jar in CI.
     */
    public fun fixtures(fixturesDir: Path): List<ReplayFixture> =
        DriftFixtureKind.entries.map { kind ->
            ReplayFixture(
                name = kind.fixtureName,
                checkedInAt = fixturesDir.resolve(kind.fixtureName),
                ticks = kind.ticks,
                identity = ::identity,
                record = ::record,
            )
        }

    /** The task a reader is sent to when a fixture of this world has gone stale. */
    public const val GRADLE_TASK: String = ":udea-replay:test"

    private const val PROTO_MIX: Int = 31
}

/**
 * `--update-replay-fixtures` for this world: `--fixtures-dir <dir>`, optionally `--dry-run`.
 *
 * Deliberately a separate entry point from the CI one, and deliberately not wired into anything
 * `check` runs. Regenerating a fixture is how a gate is silenced, so it is a thing somebody types
 * on purpose - the same bargain `udeaWriteProtocolLock` strikes with `net-protocol.lock`.
 *
 * It shares [ReplayFixtures.reconcile] with the `--update-goldens`-shaped route through
 * `:udea-replay:test -Dupdate.replay.fixtures=true`, so the two front doors cannot disagree about
 * what "stale" means or about what they write. `--dry-run` is the same call with `update = false`,
 * which is what makes the reporting half of this runnable without writing anything.
 */
public object DriftFixturesMain {

    @JvmStatic
    public fun main(args: Array<String>) {
        var dir: Path? = null
        var update = true
        var at = 0
        while (at < args.size) {
            when (args[at]) {
                "--fixtures-dir" -> {
                    require(at + 1 < args.size) { "--fixtures-dir needs a directory after it" }
                    dir = Path.of(args[at + 1]).toAbsolutePath().normalize()
                    at++
                }

                "--dry-run" -> update = false

                else -> throw IllegalArgumentException("unknown option '${args[at]}'")
            }
            at++
        }
        val fixturesDir = requireNotNull(dir) { "--fixtures-dir is required" }
        val statuses = ReplayFixtures.reconcile(
            DriftFixtureRecorder.fixtures(fixturesDir),
            update = update,
        )
        for (status in statuses) println(status.describe())
        // A dry run still has to fail on a stale fixture, or "report what is stale" and "say
        // nothing" would print the same exit code.
        if (!update) ReplayFixtures.requireCurrent(statuses, DriftFixtureRecorder.GRADLE_TASK)
    }
}
