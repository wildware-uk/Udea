package dev.wildware.moba.replay

import dev.wildware.udea.core.Tick
import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.PeerId
import dev.wildware.udea.replay.equality.ReplayDigest
import dev.wildware.udea.replay.equality.ReplayDigestIo
import dev.wildware.udea.replay.equality.ReplayDigestRecorder
import dev.wildware.udea.replay.equality.ReplayEquality
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The gate replays this game.** Issue #172.
 *
 * ## What was wrong, in one sentence
 *
 * The three `replay-equality` legs and the three `replay-equality-nightly` legs replayed
 * `DriftWorld`, a purpose-built fixture whose author routed its trigonometry through `StrictMath`
 * because he knew exactly which call was the trap. That world is *written to be deterministic*, so
 * six green legs reported the health of their own fixture. `moba` is the thing Phase 7 exists to
 * make deterministic, and nothing in this repository had ever replayed it on two operating systems
 * and compared it field by field.
 *
 * ## What this class asserts, and what it deliberately leaves to the sibling
 *
 * The half that needs **this game** to answer: that the checked-in bytes are rebuildable, that a
 * planted one-ulp divergence in `moba` is caught and named against a real `moba` component, and
 * that every fixture `ci.yml` names is one this game has.
 *
 * The half that is about the *workflow's shape* - which directory a leg's `--out` resolves into,
 * which job may carry the plant, whether the nightly can run on a pull request - stays in
 * `ReplayEqualityProofTest`, beside the entry point and the join that implement it. Two classes
 * asserting the same lines would drift apart, and the one that ran first would decide.
 *
 * ## Why the digests here are in-process and the CI ones are not
 *
 * A cross-process comparison is what `:moba:udeaReplayEqualityProof` does - five JVMs, because two
 * runs inside one process share a warmed JIT, a loaded class hierarchy and one set of static
 * initialisers, which is most of what a cross-machine comparison is asking about. That belongs in
 * a task run by name. What belongs on every push is the cheaper claim underneath it: that the
 * planted perturbation reaches a field, and that the report names the field it reached.
 */
class MobaReplayEqualityTest {

    private val dir: Path = createTempDirectory("moba-replay-equality")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    /** `ci.yml`, from the repository root above this project. */
    private val workflow: String by lazy {
        Files.readString(projectDir.resolve("../.github/workflows/ci.yml").normalize())
    }

    /**
     * `ci.yml` with its comment lines dropped.
     *
     * A fence that fires because somebody *wrote about* a fixture in a comment is as wrong as one
     * that misses a real use of it, and this workflow is heavily commented on purpose - the
     * `replay-equality` job is thirty lines of steps under seventy of prose, and that prose names
     * `moba-36000.udearep` while explaining the job. `a comment naming a fixture is not a job
     * running one` is the control.
     */
    private val workflowCode: String by lazy {
        workflow.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
    }

    @Test
    fun `the checked-in gate fixture is regenerable, input for input`() {
        // Regenerated from the specified `java.util.Random` LCG, so this holds on any JVM. It is
        // what makes a checked-in binary reviewable rather than trusted: anybody can rebuild the
        // bytes from `MobaFixtureRecorder` and diff the input stream against the file.
        //
        // The recorded *hash* stream is deliberately not compared. Those hashes are whatever the
        // machine that generated the fixture produced, and whether another machine reproduces them
        // is the question the whole cross-OS job exists to ask - asserting it here would make this
        // test go red on the second platform for the gate's own reason, in the wrong job, with the
        // recording machine cast as the authority.
        val checkedIn = MobaFixtureRecorder.readCheckedIn(MobaFixtureKind.PR)
        val rebuilt = MobaFixtureRecorder.record(MobaFixture.PR_TICKS)

        assertEquals(checkedIn.tickCount, rebuilt.tickCount)
        assertEquals(checkedIn.firstTick, rebuilt.firstTick)
        assertEquals(checkedIn.header.identity, rebuilt.header.identity)
        assertEquals(checkedIn.schema.hash, rebuilt.schema.hash)

        val fromFile = InputSample(MobaReplay.SCHEMA)
        val fromRebuild = InputSample(MobaReplay.SCHEMA)
        var moved = 0
        for (index in 0 until checkedIn.tickCount) {
            val tick = checkedIn.firstTick + index.toLong()
            checkedIn.sampleInto(tick, PeerId(0), fromFile)
            rebuilt.sampleInto(tick, PeerId(0), fromRebuild)
            assertTrue(
                fromFile.contentEquals(fromRebuild),
                "the pilot diverges at $tick: the file holds $fromFile and a rebuild produces " +
                    "$fromRebuild, so the checked-in fixture is not what MobaFixtureRecorder " +
                    "produces and cannot be regenerated",
            )
            if (!fromFile.isIdle()) moved++
        }
        assertTrue(
            moved > MobaFixture.PR_TICKS / 4,
            "only $moved of ${MobaFixture.PR_TICKS} recorded ticks carry any input at all; a " +
                "recording of an idle champion would round-trip perfectly and would make the " +
                "gate a test of the seed rather than of the recording",
        )
    }

    @Test
    fun `two honest legs of the gate fixture agree cell for cell`() {
        // The control for the test below, and it has to come first in the reading order: a
        // comparison that reported a divergence between two identical runs would make the planted
        // one prove nothing at all.
        val result = ReplayEquality.replayEquals(digest("leg-a", null), digest("leg-b", null))

        assertTrue(result.isEqual, "two honest legs of the same fixture disagreed:\n${result.describe()}")
        assertEquals(MobaFixture.PR_TICKS, result.ticksCompared)
    }

    @Test
    fun `a planted one-ulp divergence is caught and names a real moba component and field`() {
        // Issue #172's second acceptance criterion, made a test rather than only a task. The
        // whole point of moving the gate is that a divergence now names something in the game:
        // `Drifter.x` is a field of a world that exists to be perturbed, and `Position.x` is
        // where the champion is standing.
        val result = ReplayEquality.replayEquals(
            digest("leg-a", null),
            digest("leg-planted", MobaFixture.PLANT_TICK),
        )

        assertTrue(result.isEqual.not(), "a planted one-ulp divergence was not caught at all")
        assertEquals(
            MobaFixture.PLANT_TICK, result.tick,
            "the divergence was reported at ${result.tick} and was planted at " +
                "${MobaFixture.PLANT_TICK}; a gate that names the wrong tick sends a bisect to " +
                "a consequence",
        )
        val cell = result.divergences.singleOrNull()
        assertNotNull(
            cell,
            "one ulp on one field of one entity must show up as exactly one differing cell on " +
                "the tick it is planted; got ${result.divergingCells}:\n${result.describe()}",
        )
        assertEquals("dev.wildware.moba.Position", cell.componentName)
        assertEquals("x", cell.fieldName)
        assertEquals(
            ReplayEquality.HISTORY_TICKS, cell.history.size,
            "spec 7 asks a cross-OS failure to print the preceding " +
                "${ReplayEquality.HISTORY_TICKS} ticks of the differing field",
        )
        assertTrue(
            cell.history.all { it.agreed },
            "the five ticks before a one-ulp plant must all have agreed, or the plant is not " +
                "what diverged:\n${result.describe()}",
        )
        // The rendered form, because that is what a reader of a job summary actually sees.
        assertContains(result.describe(), "Position.x")
        assertContains(result.describe(), "at ${MobaFixture.PLANT_TICK}")
    }

    @Test
    fun `every fixture the workflow names is one this game has`() {
        // Total rather than per-job: every `-Pudea.replay.fixture=` in the file, whichever job it
        // is in, has to resolve. A name this game does not have fails inside a classpath lookup
        // at three in the morning, and `MobaFixtureKind.byName` is what the leg resolves it
        // through - so this asks the resolver, rather than comparing two strings that were typed
        // by the same person on the same afternoon.
        val named = Regex("-Pudea\\.replay\\.fixture=(\\S+)").findAll(workflowCode)
            .map { it.groupValues[1] }.toList()

        assertTrue(
            named.isNotEmpty(),
            "no job names a fixture. The nightly must, or it replays the gate's short recording " +
                "and asks nothing the gate has not already asked on every push.",
        )
        for (name in named) {
            // Throws with the names that do exist, which is the message worth having.
            MobaFixtureKind.byName(name)
        }
        assertContains(named, MobaFixture.NIGHTLY_FIXTURE)
    }

    @Test
    fun `a comment naming a fixture is not a job running one`() {
        // The control for `workflowCode`. `ci.yml`'s prose names `moba-36000.udearep` while
        // explaining why the nightly is a second job, so a fence over the raw text would pass on
        // a workflow whose every `run:` line had been commented out. Run the known negative
        // before trusting the yes.
        val commented = """
            # -Pudea.replay.fixture=not-a-fixture-at-all.udearep
              run: ./gradlew :moba:udeaReplayDigest
        """.trimIndent()
        val stripped = commented.lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")

        assertTrue(
            "not-a-fixture-at-all" !in stripped,
            "the comment stripper this class's fences read through lets a commented-out line " +
                "count as a running one:\n$stripped",
        )
        assertContains(stripped, ":moba:udeaReplayDigest")
        // And that the real file really is being read through it, rather than the raw text
        // happening to agree: the workflow's prose does name the nightly fixture.
        assertContains(workflow, "# One thing: `-Pudea.replay.fixture`")
    }

    @Test
    fun `the gate replays the short recording and the nightly the long one`() {
        // The one property no `BuildIdentity` check can see. A recording of the right build and
        // the wrong length replays perfectly and simply stops early, so a nightly asking for
        // 36000 ticks would quietly measure however many the file happens to hold.
        assertEquals(MobaFixtureKind.PR, MobaFixtureKind.entries.first())
        assertEquals(
            MobaFixtureKind.PR.ticks,
            MobaFixtureRecorder.readCheckedIn(MobaFixtureKind.PR).tickCount,
        )
        assertEquals(
            MobaFixtureKind.NIGHTLY.ticks,
            MobaFixtureRecorder.readCheckedIn(MobaFixtureKind.NIGHTLY).tickCount,
        )
        assertTrue(
            MobaFixtureKind.NIGHTLY.ticks > MobaFixtureKind.PR.ticks,
            "the nightly's fixture is the long one; if it is not longer than the one every push " +
                "replays then the nightly costs three machines an hour and asks nothing new",
        )
    }

    @Test
    fun `the proof task plants at the tick this game declares`() {
        // A Gradle script cannot read a Kotlin constant out of a source set it is about to
        // compile, so the plant tick is a literal in `moba/build.gradle.kts` as well as here.
        // Without this the proof would keep passing while asserting about a tick nothing plants
        // at any more - and `MobaFixture.PLANT_TICK` is what the test above uses, so the two
        // halves of the same claim would be about different ticks.
        assertContains(
            Files.readString(projectDir.resolve("build.gradle.kts")),
            "val replayPlantTick = \"${MobaFixture.PLANT_TICK.value}\"",
        )
    }

    /** One leg, replayed in this process, optionally carrying the plant. */
    private fun digest(label: String, plantAt: Tick?): ReplayDigest {
        val out = dir.resolve("$label.udeaeq")
        ReplayDigestRecorder.record(
            recording = MobaFixtureRecorder.readCheckedIn(MobaFixtureKind.PR),
            factory = MobaDigestMain.worlds(plantAt),
            registry = MobaReplay.REGISTRY,
            output = out,
            label = label,
            fixture = MobaFixtureKind.PR.fixtureName,
            gradleProject = MobaDigestMain.GRADLE_PROJECT,
        )
        return ReplayDigestIo.read(out)
    }

    private val projectDir: Path = Path.of(
        System.getProperty("udea.moba.projectDir")
            ?: error("udea.moba.projectDir is not set; the test task must pass it"),
    )
}
