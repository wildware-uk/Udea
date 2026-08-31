package dev.wildware.udea.replay.equality.fixture

import dev.wildware.udea.replay.BuildIdentity
import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.ReplayRecorder
import dev.wildware.udea.replay.ReplayRecording
import java.nio.file.Files
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
                if (pilot.nextInt(PULSE_ODDS) == 0) pulses++
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

    /** Reads the checked-in fixture from the classpath. */
    public fun readCheckedIn(): ReplayRecording {
        val bytes = checkNotNull(javaClass.getResourceAsStream(DriftFixture.PR_RESOURCE)) {
            "${DriftFixture.PR_RESOURCE} is not on the classpath. It is checked in under " +
                "udea-replay/src/testFixtures/resources; regenerate it with DriftFixtureRecorder."
        }.use { it.readBytes() }
        return ReplayRecording.decode(bytes)
    }

    /** Regenerates the checked-in fixture at [path]. Used by the generator entry point only. */
    public fun writeTo(path: Path, ticks: Int = DriftFixture.PR_TICKS) {
        Files.createDirectories(path.toAbsolutePath().parent)
        record(ticks).writeTo(path)
    }

    private const val PROTO_MIX: Int = 31
}

/**
 * Regenerates the checked-in fixture: `--out <path>` and optionally `--ticks <n>`.
 *
 * Deliberately a separate entry point from the CI one, and deliberately not wired into any task
 * this build runs. Regenerating a fixture is how a gate is silenced, so it is a thing somebody
 * types on purpose. The general form of it - `--update-replay-fixtures` across every fixture a
 * game has - is issue #165.
 */
public object DriftFixtureMain {

    @JvmStatic
    public fun main(args: Array<String>) {
        var out: Path? = null
        var ticks = DriftFixture.PR_TICKS
        var at = 0
        while (at < args.size) {
            when (args[at]) {
                "--out" -> {
                    require(at + 1 < args.size) { "--out needs a path after it" }
                    out = Path.of(args[at + 1])
                    at++
                }

                "--ticks" -> {
                    require(at + 1 < args.size) { "--ticks needs a number after it" }
                    ticks = args[at + 1].toInt()
                    at++
                }

                else -> throw IllegalArgumentException("unknown option '${args[at]}'")
            }
            at++
        }
        val target = requireNotNull(out) { "--out is required" }
        DriftFixtureRecorder.writeTo(target, ticks)
        val recording = ReplayRecording.readFrom(target)
        println(
            "wrote $target: ${recording.tickCount} tick(s) from ${recording.firstTick} to " +
                "${recording.endTick}, ${Files.size(target)} bytes",
        )
    }
}
