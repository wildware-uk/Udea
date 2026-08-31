package dev.wildware.moba.replay

import dev.wildware.moba.MobaControls
import dev.wildware.moba.MobaGame
import dev.wildware.udea.replay.BuildIdentity
import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.ReplayRecorder
import dev.wildware.udea.replay.ReplayRecording
import dev.wildware.udea.replay.equality.ReplayFixtureRef
import dev.wildware.udea.replay.fixture.ReplayFixture
import dev.wildware.udea.replay.fixture.ReplayFixtures
import java.nio.file.Path
import java.util.Random

/**
 * Writes and reads the checked-in `moba` `.udearep` files the `replay-equality` jobs replay.
 *
 * ## The pilot is a specified LCG, and it is not the simulation
 *
 * `java.util.Random` is one of the few classes whose *algorithm* is in its specification - a
 * 48-bit linear congruential generator with named constants - so `Random(seed)` produces the same
 * stream of bits on every conforming JVM. That is what makes [record] regenerable: anybody can
 * rebuild these bytes from this file and get the same input stream, on any machine, which is what
 * lets a reviewer check a checked-in binary instead of trusting it.
 *
 * It never enters a world. It authors a recording, offline, once, by writing into the
 * [InputSample] a `MobaReplayWorld` then hands to `ReplayIntentSource` - the same seam a keyboard
 * sits behind. Simulation randomness stays `RngService` and its named streams, which is what
 * every AI-driven unit on the level draws from, and is why the recording only has to carry the
 * champion.
 *
 * ## Recorded through the replay world, not beside it
 *
 * [record] drives a real [MobaReplayWorld], the very class a replay drives. A recorder that built
 * its own host and wired its own intent source would be recording a world assembled by different
 * code from the one that plays it back, and the first thing to go wrong would be invisible: two
 * assemblies that agree today and diverge the day one of them gains a module.
 *
 * ## The recording's own hash stream is one machine's answer
 *
 * A `.udearep` carries the world hash per tick, and the hashes in these files are whatever the
 * machine that generated them produced. That is exactly the number the cross-OS gate exists to
 * ask about, so nothing here asserts a replay against it: `ReplayDigestRecorder` counts the
 * mismatches and reports them, and the verdict comes from comparing two legs against each other.
 * A test that gated on the stored hashes would make the fixture's birthplace the authority and
 * blame the other platform for a difference both sides are equally responsible for.
 */
public object MobaFixtureRecorder {

    /** Ticks the pilot holds one direction for, at the least. */
    public const val MIN_HOLD: Int = 12

    /** How much longer than [MIN_HOLD] a hold can run. */
    public const val HOLD_SPREAD: Int = 36

    /** The pilot's primary attack, as one draw in this many. */
    public const val ATTACK_ONE_IN: Int = 25

    /** The pilot's second attack, as one draw in this many. */
    public const val ATTACK_TWO_IN: Int = 60

    /**
     * Records [ticks] of piloted play into a fresh headless `moba`.
     *
     * The order in the loop is [ReplayRecorder.record]'s contract: read the tick about to run,
     * apply this tick's input, run it, hash **after**. A hash taken before would describe the
     * previous tick and the whole stream would be off by one - which for a bisect is the
     * difference between landing on the cause and landing on its first consequence.
     *
     * @return the sealed recording, ready to be written or replayed.
     */
    public fun record(ticks: Int = MobaFixture.PR_TICKS): ReplayRecording {
        require(ticks > 0) { "a fixture of $ticks tick(s) proves nothing" }
        // The world a replay drives, built the way a replay builds it. `MobaReplay.worlds()`
        // is not used here only because it takes the tick to start at, and what a recording
        // starts at is whatever a fresh boot lands on rather than a number chosen in advance.
        val world = MobaReplayWorld(MobaReplay.bootHeadless())
        val recorder = ReplayRecorder(
            identityWithoutSchema = MobaReplay.identityOf(world.host),
            schema = MobaReplay.SCHEMA,
            peerCount = 1,
            gameId = MobaReplay.GAME_ID,
            gameVersion = MobaGame.VERSION,
        )
        val pilot = Random(MobaFixture.PILOT_SEED)
        val sample = InputSample(MobaReplay.SCHEMA)
        val slots = arrayOf(sample)
        var moveX = 0f
        var moveY = 0f
        var holdFor = 0
        try {
            repeat(ticks) {
                val tick = world.tick
                if (holdFor <= 0) {
                    // One of nine directions including the idle one, held for a while. Coarse on
                    // purpose: what the pilot has to be is *not reconstructible from the seed*,
                    // and what it must not be is a source of nondeterminism inside the tick.
                    moveX = pilot.nextInt(3) - 1f
                    moveY = pilot.nextInt(3) - 1f
                    holdFor = MIN_HOLD + pilot.nextInt(HOLD_SPREAD)
                }
                holdFor--
                sample.setAxis(MOVE_AXIS, moveX, moveY)

                // `Intent.pressCount` is this tick's count and not a rolling total - see
                // `Player`'s `isJustPressed`, which reads `presses[action] > 0` - so a press is 1
                // on the tick it happens and 0 otherwise. The drift fixture's `u8` wrap-around
                // has no analogue here for that reason.
                val primary = pilot.nextInt(ATTACK_ONE_IN) == 0
                val secondary = pilot.nextInt(ATTACK_TWO_IN) == 0
                sample.setPressed(ATTACK, primary)
                sample.setPressCount(ATTACK, if (primary) 1 else 0)
                sample.setPressed(ATTACK_2, secondary)
                sample.setPressCount(ATTACK_2, if (secondary) 1 else 0)

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
     * The four fields a replay of one of these recordings is refused over.
     *
     * Booted rather than assembled from constants, and that is the point of it: `rootSeed` is
     * read off the host that actually seeded the streams, and `protoHash` and the asset graph
     * hash are what *this build* produces. A recording made before a replicated component moved
     * an id is refused by name here rather than diverging at a tick whose cause is a renumbering.
     */
    public fun identity(): BuildIdentity = MobaReplay.identityOf(MobaReplay.bootHeadless())

    /** Reads one checked-in fixture from the classpath. */
    public fun readCheckedIn(kind: ReplayFixtureRef = MobaFixtureKind.PR): ReplayRecording {
        val bytes = checkNotNull(javaClass.getResourceAsStream(kind.resource)) {
            "${kind.resource} is not on the classpath. It is checked in under " +
                "moba/${MobaFixture.FIXTURES_DIR}; rebuild it with " +
                ReplayFixtures.updateCommand(MobaFixture.GRADLE_TASK)
        }.use { it.readBytes() }
        return ReplayRecording.decode(bytes)
    }

    /**
     * Every fixture of this game, as `--update-replay-fixtures` reconciles them.
     *
     * @param fixturesDir the source directory the bytes are checked in under, normally
     *   `moba/src/test/resources/fixtures`. A parameter rather than a constant because the caller
     *   knows where the source tree is and this class does not - it is loaded from a directory
     *   under `build/` when a test runs it, and from a jar in CI.
     */
    public fun fixtures(fixturesDir: Path): List<ReplayFixture> =
        MobaFixtureKind.entries.map { kind ->
            ReplayFixture(
                name = kind.fixtureName,
                checkedInAt = fixturesDir.resolve(kind.fixtureName),
                ticks = kind.ticks,
                identity = ::identity,
                record = ::record,
            )
        }

    /** The move axis's index in [MobaReplay.SCHEMA], which is `MobaControls`' catalog. */
    private val MOVE_AXIS: Int = MobaControls.MOVE_AXIS.value

    /** The primary attack's index in the same catalog. */
    private val ATTACK: Int = MobaControls.ATTACK_ACTION.value

    /** The second attack's index in the same catalog. */
    private val ATTACK_2: Int = MobaControls.ATTACK_2_ACTION.value
}

/**
 * `--update-replay-fixtures` for `moba`: `--fixtures-dir <dir>`.
 *
 * Deliberately not wired into anything `check` runs. Regenerating a fixture is how a gate gets
 * silenced, so it is a command somebody types on purpose - the same bargain `udeaWriteProtocolLock`
 * strikes with `net-protocol.lock`. It exists because the alternative is a checked-in binary
 * nobody can reproduce, and a reviewer has to be able to rebuild the bytes to check them.
 *
 * It shares [ReplayFixtures.reconcile] with the `--update-goldens`-shaped route through
 * `:moba:test -Dupdate.replay.fixtures=true`, so the two front doors cannot disagree about what
 * "stale" means or about what they write.
 */
public object MobaFixturesMain {

    @JvmStatic
    public fun main(args: Array<String>) {
        var dir: Path? = null
        var at = 0
        while (at < args.size) {
            when (args[at]) {
                "--fixtures-dir" -> {
                    require(at + 1 < args.size) { "--fixtures-dir needs a directory after it" }
                    dir = Path.of(args[at + 1]).toAbsolutePath().normalize()
                    at++
                }

                else -> throw IllegalArgumentException("unknown option '${args[at]}'")
            }
            at++
        }
        val fixturesDir = requireNotNull(dir) { "--fixtures-dir is required" }
        val statuses = ReplayFixtures.reconcile(
            MobaFixtureRecorder.fixtures(fixturesDir),
            update = true,
        )
        for (status in statuses) println(status.describe())
    }
}
