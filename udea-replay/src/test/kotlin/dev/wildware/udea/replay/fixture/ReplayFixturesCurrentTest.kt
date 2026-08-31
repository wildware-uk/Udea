package dev.wildware.udea.replay.fixture

import dev.wildware.udea.replay.equality.fixture.DriftFixtureKind
import dev.wildware.udea.replay.equality.fixture.DriftFixtureRecorder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checked-in `.udearep` bytes, against the build that has to replay them.
 *
 * This is the live end of `--update-replay-fixtures`: the same [ReplayFixtures.reconcile] the
 * flag drives, pointed at the real files in the source tree rather than at a synthetic one.
 * `ReplayFixtureUpdateTest` covers the mechanism's cases - a moved `protoHash`, a missing file,
 * a wrong length, bytes that are not a recording - because none of those can be produced on
 * demand out of a fixture that is supposed to be current.
 *
 * ## Why this cannot be a slow test
 *
 * It decodes each fixture and compares the four identity fields and the tick count. It does not
 * run either fixture world: replaying the 36000-tick one to compare it against a rebuild would
 * put ten minutes of simulated play into `check`, which is the exact cost issue #152's scope
 * bullet 2 says a pull request must not pay. What *is* worth running per push - the recorded
 * input stream against a fresh recording of it, sample for sample - is
 * `ReplayEqualityProofTest`, and it does that for the 3600-tick fixture only.
 */
class ReplayFixturesCurrentTest {

    private val projectDir: Path = Path.of(
        System.getProperty("udea.projectDir")
            ?: error("udea.projectDir is not set; the test task must pass it"),
    )

    private val fixturesDir: Path = projectDir.resolve("src/testFixtures/resources/fixtures")

    @Test
    fun `every checked-in replay fixture can be replayed by this build`() {
        val update = ReplayFixtures.updateRequested(System::getProperty)
        val statuses = ReplayFixtures.reconcile(
            DriftFixtureRecorder.fixtures(fixturesDir),
            update = update,
        )
        for (status in statuses) println(status.describe())

        // With the flag set this cannot fail, which is the point of the flag: the run that
        // rebuilds the bytes is the run that is allowed to find them wrong.
        ReplayFixtures.requireCurrent(statuses, DriftFixtureRecorder.GRADLE_TASK)

        assertEquals(
            DriftFixtureKind.entries.map { it.fixtureName },
            statuses.map { it.fixture.name },
            "every fixture this world declares must be reconciled, or one of them goes stale " +
                "with nothing looking at it",
        )
    }

    @Test
    fun `the nightly fixture is the length the nightly job asks for`() {
        // The one property no `BuildIdentity` check can see, asserted against the constant the
        // workflow's `-Pudea.replay.fixture` resolves through. A recording of the right build and
        // the wrong length replays perfectly and stops early, so a nightly asking for 36000 ticks
        // would quietly measure however many the file happens to hold.
        val nightly = DriftFixtureRecorder.readCheckedIn(DriftFixtureKind.NIGHTLY)

        assertEquals(DriftFixtureKind.NIGHTLY.ticks, nightly.tickCount)
        assertTrue(
            DriftFixtureKind.NIGHTLY.ticks > DriftFixtureKind.PR.ticks,
            "the nightly fixture is the long one; if it is not longer than the one every push " +
                "replays then the nightly job costs a machine an hour and asks nothing new",
        )
    }
}
