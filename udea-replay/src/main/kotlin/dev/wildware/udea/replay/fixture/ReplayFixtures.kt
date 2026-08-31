package dev.wildware.udea.replay.fixture

import dev.wildware.udea.replay.BuildIdentity
import dev.wildware.udea.replay.ReplayFormatException
import dev.wildware.udea.replay.ReplayRecording
import java.nio.file.Files
import java.nio.file.Path

/**
 * One checked-in `.udearep` a determinism gate replays, and how to rebuild it.
 *
 * A fixture is checked in rather than generated per run because the cross-OS gate's whole claim
 * is that two machines replaying *the same bytes* agree; generating the input on each leg would
 * make the two legs agree about a file neither of them ever compared. The cost of checking bytes
 * in is that they go stale, which is what [ReplayFixtures] and `--update-replay-fixtures` are
 * for.
 *
 * @param name the file's name, as a digest header and a failure message both spell it.
 * @param checkedInAt where the bytes live in the source tree. Regeneration writes here, so it is
 *   a source path and not a build-directory one - a fixture rebuilt into `build/` is a fixture
 *   nobody reviews and `git status` never mentions.
 * @param ticks how long the recording is, as a count of ticks. The gate's own unit; a recording
 *   of the right build and the wrong length replays perfectly and simply stops early.
 * @param identity this build's [BuildIdentity] for the recording. Deliberately a function: it is
 *   derived from generated ids and an asset graph, and both move.
 * @param record rebuilds the recording at a given tick count.
 */
public class ReplayFixture(
    public val name: String,
    public val checkedInAt: Path,
    public val ticks: Int,
    private val identity: () -> BuildIdentity,
    private val record: (Int) -> ReplayRecording,
) {

    init {
        require(ticks > 0) { "a fixture of $ticks tick(s) proves nothing" }
    }

    /** What this build would refuse a recording of this fixture over. */
    public fun identity(): BuildIdentity = identity.invoke()

    /** Rebuilds the recording, in memory. Runs the fixture world for [ticks] ticks. */
    public fun record(): ReplayRecording = record.invoke(ticks)

    override fun toString(): String = "$name ($ticks tick(s) at $checkedInAt)"
}

/**
 * What one fixture's checked-in bytes turned out to be, and what was done about it.
 *
 * Carries the reason as text rather than as a structure because every consumer of it - a test
 * failure, a Gradle task's console output, a CI job summary - prints it, and the one thing a
 * reader needs is which identity field moved and what both sides hold.
 */
public class ReplayFixtureStatus(
    /** The fixture this is about. */
    public val fixture: ReplayFixture,
    /** What the reconcile found, and whether it wrote. */
    public val outcome: Outcome,
    /** Why, in a sentence a reader can act on. Never empty. */
    public val detail: String,
) {

    /** The four states a checked-in fixture can be in when a build looks at it. */
    public enum class Outcome {
        /** The bytes are there and this build can replay them. Nothing was written. */
        CURRENT,

        /** The bytes were rebuilt, because `--update-replay-fixtures` was asked for. */
        REGENERATED,

        /** There are no bytes at [ReplayFixture.checkedInAt]. */
        MISSING,

        /** The bytes are there and this build cannot replay them. */
        REFUSED,
        ;

        /** Whether a build that has not been asked to regenerate should stop here. */
        public val isFailure: Boolean get() = this == MISSING || this == REFUSED
    }

    /** One line: the fixture, the outcome and the reason. */
    public fun describe(): String = "${fixture.name}: $outcome - $detail"

    override fun toString(): String = describe()
}

/**
 * `--update-replay-fixtures`: the one command that rebuilds every checked-in replay fixture.
 *
 * ## The failure it answers
 *
 * A `.udearep` carries the [BuildIdentity] of the build that recorded it, and a replay refuses it
 * the moment any of those four fields moves. `protoHash` is one of them, and it moves whenever a
 * replicated component is added or removed - issue #167 moved it by honouring
 * `visibility = OwnerOnly` in the snapshot writer, and every recording made before that commit
 * stopped being replayable in it. Without a regeneration path the determinism gate then fails for
 * a reason that has nothing to do with determinism, and the person who moved the id has to work
 * out by hand which files to rebuild and how.
 *
 * ## The convention is `--update-goldens`, not a second one
 *
 * `docs/engineering-standards.md` §5 names `--update-goldens`, and what is actually typed for it
 * is `./gradlew :udea-net:test -Dupdate.goldens=true` - Gradle has no `--update-goldens` option
 * for a plain `Test` task, so the documented flag is a system property. This mirrors it exactly:
 * [UPDATE_FLAG] is what it is called and [UPDATE_PROPERTY] is what is typed, and
 * [requireCurrent] prints the typed form so nobody has to work out the mapping.
 *
 * ## Regeneration is deliberate, and it is not unconditional
 *
 * [reconcile] rewrites a fixture only when it is missing or refused. A regeneration that ran
 * every time would churn a checked-in binary on every invocation, and a binary that changes on
 * every run is a diff nobody can read - which is the same reason `udeaWriteProtocolLock` exists
 * as a command somebody types rather than as a step of `build`.
 *
 * ## What "current" means here, and what it deliberately does not
 *
 * Current means *this build can replay these bytes*: the identity matches and the recording is
 * the length the fixture declares. It is emphatically not "the bytes equal a fresh recording's".
 * A `.udearep` carries one world hash per tick, and those hashes are whatever the machine that
 * recorded them produced - which is the very question the cross-OS gate exists to ask. A check
 * that compared them would make the recording machine the authority and go red on the second
 * platform, in the wrong job, for the gate's own reason. `ReplayEqualityProofTest` covers the
 * half that *is* machine-independent: the recorded input stream, replayed out of the file and
 * compared against a rebuild, sample for sample.
 */
public object ReplayFixtures {

    /** What this convention is called, in prose and in a message. */
    public const val UPDATE_FLAG: String = "--update-replay-fixtures"

    /** What is actually typed, mirroring `update.goldens`. */
    public const val UPDATE_PROPERTY: String = "update.replay.fixtures"

    /**
     * Whether regeneration was asked for.
     *
     * @param properties where a property is read from; `System::getProperty` in a test JVM. Named
     *   rather than read directly so that the "only the string `true`" rule is testable without a
     *   test mutating the JVM's global property table and leaking it into whatever runs next.
     */
    public fun updateRequested(properties: (String) -> String?): Boolean =
        properties(UPDATE_PROPERTY) == "true"

    /**
     * Looks at every fixture's checked-in bytes, and rebuilds the ones it has to when [update].
     *
     * Never throws for a fixture that is merely stale: a caller regenerating a set wants every
     * one of them looked at, not the first failure. [requireCurrent] is what turns the answer
     * into a build failure.
     */
    public fun reconcile(fixtures: List<ReplayFixture>, update: Boolean): List<ReplayFixtureStatus> =
        fixtures.map { reconcileOne(it, update) }

    /**
     * Fails unless every status is [ReplayFixtureStatus.Outcome.CURRENT] or regenerated.
     *
     * @param gradleTask the task that owns these fixtures, e.g. `:udea-replay:test`. It is the
     *   caller's because the fixtures are: `moba`'s live under `:moba:test` and this module's
     *   under `:udea-replay:test`, and a message naming the wrong one sends a reader to a task
     *   that would rebuild nothing.
     */
    public fun requireCurrent(statuses: List<ReplayFixtureStatus>, gradleTask: String) {
        val failures = statuses.filter { it.outcome.isFailure }
        check(failures.isEmpty()) {
            buildString {
                append(failures.size).append(" replay fixture(s) cannot be replayed by this build:")
                for (failure in failures) append("\n  ").append(failure.describe())
                append("\n\nIf that is expected - an id moved, a component was added, the fixture ")
                append("world changed - rebuild them and review the diff:\n  ")
                append(updateCommand(gradleTask))
            }
        }
    }

    /** The command [requireCurrent] prints. Spelled once, here. */
    public fun updateCommand(gradleTask: String): String =
        "./gradlew $gradleTask -D$UPDATE_PROPERTY=true"

    private fun reconcileOne(fixture: ReplayFixture, update: Boolean): ReplayFixtureStatus {
        val path = fixture.checkedInAt
        if (Files.notExists(path)) {
            return if (update) {
                regenerate(fixture, "it did not exist")
            } else {
                status(fixture, ReplayFixtureStatus.Outcome.MISSING, "no bytes at $path")
            }
        }

        val reason = staleReason(fixture, path)
            ?: return status(
                fixture,
                ReplayFixtureStatus.Outcome.CURRENT,
                "${fixture.ticks} tick(s), ${Files.size(path)} bytes, replayable by this build",
            )

        return if (update) {
            regenerate(fixture, reason)
        } else {
            status(fixture, ReplayFixtureStatus.Outcome.REFUSED, reason)
        }
    }

    /** Why [path] cannot stand as [fixture]'s bytes, or `null` when it can. */
    private fun staleReason(fixture: ReplayFixture, path: Path): String? {
        val recording = try {
            ReplayRecording.decode(Files.readAllBytes(path))
        } catch (failure: ReplayFormatException) {
            // Reported, not swallowed: the reader's own sentence says which byte disagreed, and
            // nothing this function could write in its place would say it better.
            return "the bytes at $path are not a recording this build can read: ${failure.message}"
        }
        val mismatches = recording.header.identity.mismatchesAgainst(fixture.identity())
        if (mismatches.isNotEmpty()) {
            return "this build cannot replay it - " + mismatches.joinToString("; ")
        }
        if (recording.tickCount != fixture.ticks) {
            return "it holds ${recording.tickCount} tick(s) and the fixture declares " +
                "${fixture.ticks}; a recording of the right build and the wrong length replays " +
                "perfectly and stops early"
        }
        return null
    }

    private fun regenerate(fixture: ReplayFixture, reason: String): ReplayFixtureStatus {
        val path = fixture.checkedInAt
        Files.createDirectories(path.toAbsolutePath().parent)
        fixture.record().writeTo(path)
        return status(
            fixture,
            ReplayFixtureStatus.Outcome.REGENERATED,
            "rebuilt because $reason; now ${fixture.ticks} tick(s), ${Files.size(path)} bytes at $path",
        )
    }

    private fun status(fixture: ReplayFixture, outcome: ReplayFixtureStatus.Outcome, detail: String) =
        ReplayFixtureStatus(fixture, outcome, detail)
}
