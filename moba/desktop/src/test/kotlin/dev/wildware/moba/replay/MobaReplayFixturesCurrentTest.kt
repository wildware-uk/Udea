package dev.wildware.moba.replay

import dev.wildware.udea.replay.fixture.ReplayFixtures
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checked-in `moba` `.udearep` bytes, against the build that has to replay them.
 *
 * This is the live end of `--update-replay-fixtures` for this game: the same
 * [ReplayFixtures.reconcile] the flag drives, pointed at the real files in the source tree rather
 * than at a synthetic one. `ReplayFixtureUpdateTest` in `udea-replay` covers the mechanism's cases
 * - a moved `protoHash`, a missing file, a wrong length, bytes that are not a recording - because
 * none of those can be produced on demand out of a fixture that is supposed to be current.
 *
 * ## Why it matters more here than it did for the fixture world
 *
 * `DriftComponents` carries no `@Replicated` and its identity is a function of one source file, so
 * its recordings go stale only when somebody edits that file. `moba`'s `protoHash` moves whenever
 * a replicated component is added or removed, its asset graph hash moves whenever an asset does,
 * and its input schema hash moves whenever a key is rebound - all of which is ordinary gameplay
 * work by somebody who has never heard of this gate. Without this test the first symptom would be
 * three red CI legs on an unrelated pull request, with `ReplayRefusedException` naming a field
 * whose connection to the change is not obvious. With it, `:moba:test` fails on the machine that
 * made the change and prints the one command that fixes it.
 *
 * ## Why it is not slow
 *
 * It decodes each fixture and compares the four identity fields and the tick count. It does not
 * replay either one: replaying the 36000-tick fixture to compare it against a rebuild would put
 * ten minutes of simulated play into `check`, which is the cost issue #152's scope says a pull
 * request must not pay. What *is* worth running per push - the recorded input stream against a
 * fresh recording of it, sample for sample - is `MobaReplayEqualityTest`, for the short one only.
 */
class MobaReplayFixturesCurrentTest {

    private val projectDir: Path = Path.of(
        System.getProperty("udea.moba.projectDir")
            ?: error("udea.moba.projectDir is not set; the test task must pass it"),
    )

    private val fixturesDir: Path = projectDir.resolve(MobaFixture.FIXTURES_DIR)

    @Test
    fun `every checked-in moba replay fixture can be replayed by this build`() {
        val update = ReplayFixtures.updateRequested(System::getProperty)
        val statuses = ReplayFixtures.reconcile(
            MobaFixtureRecorder.fixtures(fixturesDir),
            update = update,
        )
        for (status in statuses) println(status.describe())

        // With the flag set this cannot fail, which is the point of the flag: the run that
        // rebuilds the bytes is the run that is allowed to find them wrong.
        ReplayFixtures.requireCurrent(statuses, MobaFixture.GRADLE_TASK)

        assertEquals(
            MobaFixtureKind.entries.map { it.fixtureName },
            statuses.map { it.fixture.name },
            "every fixture this game declares must be reconciled, or one of them goes stale with " +
                "nothing looking at it",
        )
    }
}
