package dev.wildware.udea.replay.equality

import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.equality.fixture.DriftFixture
import dev.wildware.udea.replay.equality.fixture.DriftFixtureRecorder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wiring nobody else checks: the build script, the checked-in bytes, and the CI workflow.
 *
 * Everything the `replay-equality` job does is a class with a `main` so that it can be tested, but
 * three joins between those classes and their callers are plain text and would otherwise drift in
 * silence: the tick the Gradle proof plants at, the entry points `ci.yml` names, and whether the
 * checked-in fixture can be rebuilt at all. Each is one assertion here and none of them can be
 * caught by anything else.
 */
class ReplayEqualityProofTest {

    private val projectDir: Path = Path.of(
        System.getProperty("udea.projectDir")
            ?: error("udea.projectDir is not set; the test task must pass it"),
    )

    private val buildScript: String by lazy { Files.readString(projectDir.resolve("build.gradle.kts")) }

    private val workflow: String by lazy {
        Files.readString(projectDir.resolve("../.github/workflows/ci.yml").normalize())
    }

    @Test
    fun `the proof task plants at the tick the fixture declares`() {
        // The Gradle script cannot read a Kotlin constant out of a source set it is about to
        // compile, so the tick is a literal in two places. This is what stops the two drifting:
        // move the constant and the proof would keep passing while asserting about a tick nothing
        // plants at any more.
        assertContains(buildScript, "val plantTick = \"${DriftFixture.PLANT_TICK.value}\"")
    }

    @Test
    fun `the proof task asserts on the four things a cross-OS failure has to name`() {
        // Spec 7 asks for the tick, the entity, the component and field, and the preceding five
        // ticks of that field's history. If somebody weakens the proof's checks, this fails.
        val required = listOf(
            "\"at t\$expectedTick\"",
            "\"Drifter.x\"",
            "\"NetId(\"",
            "\"the preceding ${ReplayEquality.HISTORY_TICKS} tick(s)\"",
        )
        for (needle in required) {
            assertContains(buildScript, needle, message = "udeaReplayEqualityProof no longer checks $needle")
        }
    }

    @Test
    fun `the checked-in fixture is regenerable, input for input`() {
        // Regenerated from the specified `java.util.Random` LCG, so this holds on any JVM.
        //
        // The recorded *hash* stream is deliberately not compared. Those hashes are whatever the
        // machine that generated the fixture produced, and whether another machine reproduces them
        // is the question the whole cross-OS job exists to ask - asserting it here would make this
        // test go red on the second platform for the gate's own reason, in the wrong job, with the
        // recording machine cast as the authority.
        val checkedIn = DriftFixtureRecorder.readCheckedIn()
        val rebuilt = DriftFixtureRecorder.record(DriftFixture.PR_TICKS)

        assertEquals(checkedIn.tickCount, rebuilt.tickCount)
        assertEquals(checkedIn.firstTick, rebuilt.firstTick)
        assertEquals(checkedIn.header.identity, rebuilt.header.identity)

        val fromFile = InputSample(DriftFixture.SCHEMA)
        val fromRebuild = InputSample(DriftFixture.SCHEMA)
        for (index in 0 until checkedIn.tickCount) {
            val tick = checkedIn.firstTick + index.toLong()
            checkedIn.sampleInto(tick, dev.wildware.udea.replay.PeerId(0), fromFile)
            rebuilt.sampleInto(tick, dev.wildware.udea.replay.PeerId(0), fromRebuild)
            assertEquals(
                fromFile.axisX(DriftFixture.AXIS_MOVE), fromRebuild.axisX(DriftFixture.AXIS_MOVE),
                "the pilot's steering diverges at $tick, so the checked-in fixture is not what " +
                    "DriftFixtureRecorder produces and cannot be regenerated",
            )
            assertEquals(
                fromFile.pressCount(DriftFixture.ACTION_PULSE),
                fromRebuild.pressCount(DriftFixture.ACTION_PULSE),
                "the pilot's presses diverge at $tick",
            )
        }
    }

    @Test
    fun `the workflow runs the entry points these tests cover, and no logic of its own`() {
        // `ci.yml` cannot be executed here, so what is checkable is that it delegates: the job
        // must invoke the two Gradle tasks and must not reimplement the comparison in shell.
        assertContains(workflow, "replay-equality")
        assertContains(workflow, "udeaReplayDigest")
        assertContains(workflow, "udeaReplayEquals")
        assertTrue(
            workflow.contains("udea.replay.label"),
            "each matrix leg must label its own digest, or a divergence names neither side",
        )
    }

    @Test
    fun `the determinism job no longer claims this file has no replay-equality gate`() {
        // The `determinism` job's comment said, in capitals, that the workflow contained no
        // replay-equality gate "until that job exists". It exists now, and a document that says
        // otherwise about a gate is worse than no document.
        assertTrue(
            !workflow.contains("THIS FILE CONTAINS NO"),
            "ci.yml still carries the placeholder claiming no replay-equality gate exists",
        )
    }
}
