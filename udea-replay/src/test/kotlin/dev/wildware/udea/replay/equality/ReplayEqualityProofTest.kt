package dev.wildware.udea.replay.equality

import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.equality.fixture.DriftDigestMain
import dev.wildware.udea.replay.equality.fixture.DriftFixture
import dev.wildware.udea.replay.equality.fixture.DriftFixtureRecorder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

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

    /**
     * `udea-replay/build.gradle.kts` with everything the Kotlin compiler ignores taken out.
     *
     * Read this way because the raw text was demonstrably not enough. Writing the phrase
     * `digests` followed by a slash and a star into the KDoc above `workspaceRoot` opened a
     * *nested* block comment - Kotlin's nest - which ran to the end of the file and took all
     * eight `udeaReplay*` task registrations with it. `sh gradlew build` stayed green, because
     * none of those tasks is wired into `check`, and every assertion in this class stayed green,
     * because the text it was matching was all still there and merely switched off. The only
     * thing that noticed was `gradlew :udea-replay:tasks`.
     *
     * So the fence reads what the compiler reads. `commentsStripped` handles nesting and string
     * literals; commenting out the `replay-equality` section is in the mutation table and turns
     * four of these tests red.
     */
    private val buildScript: String by lazy {
        commentsStripped(Files.readString(projectDir.resolve("build.gradle.kts")))
    }

    /** `ci.yml` verbatim. Only the test about a comment's *content* should read this. */
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
        //
        // Against `workflowCode` rather than the raw file: a job that had been commented out
        // would satisfy every one of these against the raw text, and a step nobody runs is
        // exactly what this class exists to notice.
        assertContains(workflowCode, "replay-equality")
        assertContains(workflowCode, "udeaReplayDigest")
        assertContains(workflowCode, "udeaReplayEquals")
        assertTrue(
            workflowCode.contains("udea.replay.label"),
            "each matrix leg must label its own digest, or a divergence names neither side",
        )
    }

    @Test
    fun `the build script fence reads what the compiler reads, not what is switched off`() {
        // The helper that decides what every build-script assertion in this class sees, checked
        // in both directions. Live code and string literals survive - a slash-star inside a
        // string opens nothing - while a line comment and a block comment do not.
        val ordinary = commentsStripped(
            """
            val live = "kept"
            // val lineCommented = "gone"
            /* val blockCommented = "gone" */
            /** A KDoc. */
            val alsoLive = "kept"
            val stringWithOpener = "literal /* not a comment"
            """.trimIndent(),
        )

        assertContains(ordinary, "val live")
        assertContains(ordinary, "val alsoLive")
        assertContains(ordinary, "literal /* not a comment")
        assertTrue("lineCommented" !in ordinary, "a line comment survived the strip:\n$ordinary")
        assertTrue("blockCommented" !in ordinary, "a block comment survived the strip:\n$ordinary")
        assertTrue("A KDoc" !in ordinary, "a KDoc survived the strip:\n$ordinary")

        // And the shape that cost this module its eight `udeaReplay*` tasks: Kotlin block
        // comments nest, so a slash-star written inside a KDoc leaves the comment open when that
        // KDoc ends, and everything after it to the end of the file is switched off. The
        // stripper must agree with the compiler about that, or the fence would read a task
        // registration the compiler never saw.
        val nested = commentsStripped(
            """
            val beforeIt = "kept"
            /** A KDoc naming a path with a slash-star in it: reports/x/*.txt */
            tasks.register("udeaSwallowed") { }
            """.trimIndent(),
        )

        assertContains(nested, "val beforeIt")
        assertTrue(
            "udeaSwallowed" !in nested,
            "the stripper thinks a registration after an unclosed nested comment is live; the " +
                "Kotlin compiler does not, and that disagreement is the whole defect:\n$nested",
        )
    }

    // --- issue #169: the two ends of a path that have to name one directory -------------------
    //
    // Every leg since #152 wrote its digest into `udea-replay/digests/` while
    // `actions/upload-artifact` globbed `digests/*.udeaeq` under `$GITHUB_WORKSPACE`. The two
    // spellings were identical, so no assertion comparing the *strings* would have caught it -
    // what differed was the directory each was resolved against, and only one side of that pair
    // is code. These tests therefore resolve both: the workflow's own argument goes through the
    // entry point CI runs, and the answer is compared against the directory Actions globs.

    /** `$GITHUB_WORKSPACE`: what `actions/checkout` roots a workflow's relative paths at. */
    private val workspace: Path get() = projectDir.parent

    /**
     * `ci.yml` with its comment lines dropped.
     *
     * A fence that fires because somebody *wrote about* a path in a comment is as wrong as one
     * that misses a real second use of it, and this workflow is heavily commented on purpose -
     * the `replay-equality` job is thirty lines of steps under fifty of prose. The path tests
     * below read this; the control, a comment naming a second `-Pudea.replay.out=`, is run with
     * the mutations and leaves them green.
     */
    private val workflowCode: String by lazy {
        workflow.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
    }

    @Test
    fun `a leg's digest lands in the directory its upload step globs`() {
        val requested = gradlePropertyInWorkflow("out")
        val written = DriftDigestMain.parse(
            arrayOf("--workspace", workspace.toString(), "--label", "leg", "--out", requested),
        ).out

        // Not `Path.of`: a glob is not a legal Windows path and this test runs on the
        // `windows-latest` leg of the `build` job too.
        val glob = stepValue("Upload this leg's digest stream", "path")
        val globbedDirectory = workspace.resolve(glob.substringBeforeLast('/')).normalize()

        assertEquals(
            globbedDirectory, written.parent,
            "the leg writes its digest to a directory the upload step does not glob. That is " +
                "issue #169: the upload trips `if-no-files-found: error`, and because " +
                "`replay-equality-join` declares `needs: replay-equality` the join never runs " +
                "and nothing is ever compared.",
        )
        assertTrue(
            globMatches(glob.substringAfterLast('/'), written.fileName.toString()),
            "the upload globs '${glob.substringAfterLast('/')}', which does not match the file " +
                "the leg writes, '${written.fileName}'",
        )
    }

    @Test
    fun `the join compares the directory the workflow downloads into`() {
        // Resolved with `Path.resolve` and not with `ReplayEqualityPaths`. Both sides of this
        // comparison running through the same function would agree with each other however
        // wrong that function was, which is a check that always answers about its own subject.
        // Measured: with the resolution reverted to the pre-#169 shape, the version of this test
        // that used `ReplayEqualityPaths` on both sides still passed while the leg's did not.
        val downloadedInto = workspace
            .resolve(stepValue("Download every leg's digest", "path"))
            .toAbsolutePath().normalize()
        val compared = ReplayEqualsMain.parse(
            arrayOf("--workspace", workspace.toString(), gradlePropertyInWorkflow("streams")),
        ).streams

        assertEquals(
            listOf(downloadedInto), compared,
            "the join reads digest streams from somewhere other than where the download step " +
                "put them. The same defect as the leg's, one job further on, and it would " +
                "report `EXIT_UNUSABLE` rather than a verdict.",
        )
    }

    @Test
    fun `both entry points the workflow runs are told which directory the workspace is`() {
        // The one join in this chain that no test can execute, because a Gradle build script is
        // not on any classpath: the two `JavaExec` tasks have to pass `--workspace`, or the
        // resolution the tests above exercise is never reached with the right base in CI.
        assertContains(
            buildScript,
            "val workspaceRoot: String = rootProject.layout.projectDirectory.asFile.absolutePath",
        )
        val passes = Regex("\"--workspace\"").findAll(buildScript).count()
        assertEquals(
            2, passes,
            "`udeaReplayDigest` and `udeaReplayEquals` are the two tasks ci.yml runs and both " +
                "must pass --workspace; found $passes occurrence(s) in the build script",
        )
    }

    @Test
    fun `the workflow reads the verdict out of the file the join writes`() {
        // Derived rather than asserted twice: the join writes `summary.md` into the report
        // directory this build script declares, and the publish step reads a path relative to
        // the workspace. Two literals that happen to agree is exactly the shape of #169.
        val reportDir = Regex(
            """val replayEqualityDir: Provider<Directory> =\s*layout\.buildDirectory\.dir\("([^"]+)"\)""",
        ).find(buildScript)?.groupValues?.get(1)
            ?: fail("the build script no longer declares replayEqualityDir the way this test reads it")

        assertContains(workflow, "${projectDir.fileName}/build/$reportDir/summary.md")
    }

    @Test
    fun `exactly one leg carries the planted divergence`() {
        // `replay_plant_ulp_at` exists to prove the gate can still fail on a real run. A plant is
        // deterministic, so three legs all carrying it agree with each other, the join reports
        // EQUAL, and the run that was supposed to go red comes back green. One leg, or the proof
        // proves the opposite of what it claims.
        assertContains(workflow, "replay_plant_ulp_at")
        assertContains(workflow, "udea.replay.plantUlpAt")
        val planted = Regex("(?m)^\\s*plant: true\\s*$").findAll(workflowCode).count()
        assertEquals(
            1, planted,
            "the replay-equality matrix marks $planted leg(s) `plant: true`; it must mark one",
        )
    }

    /** The value `ci.yml` hands `-Pudea.replay.<name>`, with its Actions expressions stood in for. */
    private fun gradlePropertyInWorkflow(name: String): String {
        val found = Regex("-Pudea\\.replay\\.$name=(.+)").findAll(workflowCode)
            .map { it.groupValues[1].trim() }.toList()
        assertEquals(
            1, found.size,
            "ci.yml should hand -Pudea.replay.$name to exactly one step; found $found",
        )
        return substituteExpressions(found.single())
    }

    /** The value of [key] inside the `ci.yml` step called [stepName]. */
    private fun stepValue(stepName: String, key: String): String {
        val start = workflowCode.indexOf("- name: $stepName")
        assertTrue(start >= 0, "ci.yml has no step named '$stepName'")
        val rest = workflowCode.substring(start + "- name: $stepName".length)
        val end = rest.indexOf("\n      - ").let { if (it < 0) rest.length else it }
        val block = rest.substring(0, end)
        val value = Regex("(?m)^\\s*$key:\\s*(\\S.*)$").find(block)?.groupValues?.get(1)?.trim()
            ?: fail("the ci.yml step '$stepName' has no `$key:` line in:\n$block")
        return substituteExpressions(value)
    }

    /**
     * `${'$'}{{ matrix.os }}` becomes a stand-in, so a path can be compared as a shape.
     *
     * The leftover check is the point of doing it here rather than inline: an unexpanded `${'$'}{{`
     * in a compared path would make every comparison below true of a string neither side ever
     * sees, which is a check that runs against the wrong subject.
     */
    private fun substituteExpressions(raw: String): String {
        val expanded = Regex("\\$\\{\\{\\s*matrix\\.\\w+\\s*}}").replace(raw, "LEG")
        assertTrue(
            !expanded.contains("\${{"),
            "this test only knows how to stand in for `matrix.*`; '$expanded' still carries an " +
                "Actions expression, so comparing it would compare a string no runner ever sees",
        )
        return expanded
    }

    /** Whether [name] matches [glob], where `*` runs up to a path separator. */
    private fun globMatches(glob: String, name: String): Boolean =
        Regex(glob.split("*").joinToString("[^/\\\\]*") { Regex.escape(it) }).matches(name)

    /**
     * [source] with its Kotlin comments removed, leaving only what the compiler acts on.
     *
     * Nesting is the point: Kotlin block comments nest, so one stray opener inside a KDoc runs
     * to the file's end and switches off everything after it. String literals are tracked so a
     * path in a string cannot open a comment that is not there.
     */
    private fun commentsStripped(source: String): String {
        val out = StringBuilder(source.length)
        var depth = 0
        var inString = false
        var index = 0
        while (index < source.length) {
            val two = if (index + 1 < source.length) source.substring(index, index + 2) else ""
            when {
                depth > 0 && two == "/*" -> { depth++; index += 2 }
                depth > 0 && two == "*/" -> { depth--; index += 2 }
                depth > 0 -> {
                    // Newlines survive, so a line-oriented assertion still sees the right shape.
                    if (source[index] == '\n') out.append('\n')
                    index++
                }
                inString && source[index] == '\\' -> { out.append(source, index, index + 2); index += 2 }
                inString && source[index] == '"' -> { inString = false; out.append('"'); index++ }
                inString -> { out.append(source[index]); index++ }
                source[index] == '"' -> { inString = true; out.append('"'); index++ }
                two == "/*" -> { depth = 1; index += 2 }
                two == "//" -> {
                    while (index < source.length && source[index] != '\n') index++
                }

                else -> { out.append(source[index]); index++ }
            }
        }
        return out.toString()
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
