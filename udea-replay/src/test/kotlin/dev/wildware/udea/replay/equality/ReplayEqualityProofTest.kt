package dev.wildware.udea.replay.equality

import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.equality.fixture.DriftDigestMain
import dev.wildware.udea.replay.equality.fixture.DriftFixture
import dev.wildware.udea.replay.equality.fixture.DriftFixtureKind
import dev.wildware.udea.replay.fixture.ReplayFixtures
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
     * *nested* block comment - Kotlin's nest - which ran to the end of the file and took every
     * task registered below it with it, `udeaReplayDigest` and `udeaReplayEquals` included.
     * `sh gradlew build` stayed green, because none of those tasks is wired into `check`, and
     * every assertion in this class stayed green, because the text it was matching was all still
     * there and merely switched off. The only thing that noticed was
     * `gradlew :udea-replay:tasks`.
     *
     * So the fence reads what the compiler reads. `commentsStripped` handles nesting and string
     * literals; commenting out the `replay-equality` section is in the mutation table, and
     * `the build script fence reads what the compiler reads` covers the stripper itself.
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
    fun `the proof task also checks that the report says how to reproduce it`() {
        // The other half of the same fence, kept as its own test so the one above keeps saying
        // what spec 7 asks for and this one says what issue #165 adds. Without it, deleting the
        // two needles from `udeaReplayEqualityProof` would leave the proof green while nothing
        // checked that the rendered guide ever reaches the file a job summary prints.
        assertContains(buildScript, "\"--- reproducing this locally ---\"")
        assertContains(buildScript, "replay.seek")
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

        // And the shape that cost this module every task registered below one KDoc: Kotlin block
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
        val requested = gradlePropertyInJob(PR_JOB, "out")
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
            arrayOf("--workspace", workspace.toString(), gradlePropertyInJob(PR_JOIN_JOB, "streams")),
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

    @Test
    fun `the test task forwards the regeneration flag to the JVM that reads it`() {
        // A `Test` task forks its own JVM and does not inherit the launcher's system properties.
        // Without this line `-Dupdate.replay.fixtures=true` reaches Gradle, is never forwarded,
        // and `ReplayFixturesCurrentTest` reports every fixture current and rebuilds nothing.
        // Nothing fails: the flag simply does not work, and a reviewer reads a green run beside a
        // fixture that never moved. That is the worst shape a regeneration path can take, and
        // this is the only place it can be caught - the build script is on no classpath.
        val flag = ReplayFixtures.UPDATE_PROPERTY
        val normalised = buildScript.replace(Regex("\\s+"), " ")

        assertContains(
            normalised,
            "systemProperty( \"$flag\", providers.systemProperty(\"$flag\")",
            message = "udea-replay/build.gradle.kts no longer forwards -D$flag to the test JVM",
        )
    }

    // --- issue #165: the nightly job, which is the same gate over a ten-times-longer recording --

    @Test
    fun `the job slicer cuts one job and not its neighbour`() {
        // The control for `jobBlock`, and the reason it exists: `replay-equality` is a prefix of
        // `replay-equality-nightly`, so a slicer anchored on a prefix would hand every assertion
        // below the wrong job's steps while returning a perfectly plausible block. That is a
        // check that runs and answers truthfully about the wrong subject.
        val pr = jobBlock(PR_JOB)
        val nightly = jobBlock(NIGHTLY_JOB)

        assertTrue(pr.isNotEmpty() && nightly.isNotEmpty(), "a job block came back empty")
        // Discriminators each block has and the other cannot: the nightly is the only job that
        // names a fixture, and the plant leg lives only on the gate. Not a path prefix - the
        // first draft of this control used one, and `nightly-digests/` contains `digests/`.
        assertTrue(
            "udea.replay.fixture" !in pr,
            "the `$PR_JOB` block reaches into `$NIGHTLY_JOB`:\n$pr",
        )
        assertTrue(
            "plant" !in nightly,
            "the `$NIGHTLY_JOB` block reaches back into `$PR_JOB`:\n$nightly",
        )
        assertTrue(
            "runs-on" in pr && "runs-on" in nightly,
            "a job block was cut short of its own body",
        )
    }

    @Test
    fun `the nightly replays the long fixture and the PR job replays the short one`() {
        // Derived from the enum rather than restated: the workflow names a fixture by string and
        // `DriftFixtureKind.byName` is what resolves it, so a typo here is a job that fails
        // inside a classpath lookup at three in the morning.
        val named = gradlePropertyInJob(NIGHTLY_JOB, "fixture")

        assertEquals(DriftFixtureKind.NIGHTLY, DriftFixtureKind.byName(named))
        assertTrue(
            "-Pudea.replay.fixture" !in jobBlock(PR_JOB),
            "the PR job now names a fixture explicitly. Issue #152's scope says the long fixture " +
                "must not block a pull request, and `DriftDigestMain` defaults to the short one, " +
                "so the PR job naming one at all is how it would come to replay the long one.",
        )
    }

    @Test
    fun `the nightly never runs on a pull request and the gate always does`() {
        // The whole reason this is a second job. A leg cannot be skipped by event without the
        // runner starting anyway, so the condition has to be on the job.
        val condition = Regex("(?m)^ {4}if: (?:>-)?\\s*\\n((?: {6}.*\\n)+)")
            .find(jobBlock(NIGHTLY_JOB))?.groupValues?.get(1)
            ?: fail("the `$NIGHTLY_JOB` job has no `if:` condition, so it runs on pull requests")

        assertContains(condition, "github.event_name")
        assertContains(condition, "'schedule'")
        assertContains(condition, "refs/heads/example")
        assertTrue(
            "pull_request" !in condition,
            "the nightly's condition mentions pull_request:\n$condition",
        )
        assertTrue(
            Regex("(?m)^ {4}if:").find(jobBlock(PR_JOB)) == null,
            "the `$PR_JOB` job has grown a condition. It is the gate: it runs on everything, and " +
                "issue #165 is explicitly not allowed to change that.",
        )
    }

    @Test
    fun `a nightly leg's digest lands in the directory its upload step globs`() {
        // Issue #169's defect, for the second pair of jobs. It is not enough that the first pair
        // resolves correctly: these are different literals in a different block, and the bug was
        // never that the code was wrong - it was that two identical-looking spellings resolved
        // against two different directories.
        val requested = gradlePropertyInJob(NIGHTLY_JOB, "out")
        val written = DriftDigestMain.parse(
            arrayOf("--workspace", workspace.toString(), "--label", "leg", "--out", requested),
        ).out

        val glob = stepValue("Upload this nightly leg's digest stream", "path")
        val globbedDirectory = workspace.resolve(glob.substringBeforeLast('/')).normalize()

        assertEquals(
            globbedDirectory, written.parent,
            "the nightly leg writes its digest where its upload step does not look, so the " +
                "upload trips `if-no-files-found: error` and nothing is ever compared",
        )
        assertTrue(
            globMatches(glob.substringAfterLast('/'), written.fileName.toString()),
            "the nightly upload globs '${glob.substringAfterLast('/')}', which does not match " +
                "'${written.fileName}'",
        )
    }

    @Test
    fun `the nightly join compares the directory the workflow downloads into`() {
        val downloadedInto = workspace
            .resolve(stepValue("Download every nightly leg's digest", "path"))
            .toAbsolutePath().normalize()
        val compared = ReplayEqualsMain.parse(
            arrayOf(
                "--workspace", workspace.toString(),
                gradlePropertyInJob(NIGHTLY_JOIN_JOB, "streams"),
            ),
        ).streams

        assertEquals(listOf(downloadedInto), compared)
    }

    @Test
    fun `the two pairs of jobs do not upload into each other's artifact names`() {
        // Two jobs downloading by pattern into one workspace. `replay-digest-*` and
        // `replay-nightly-digest-*` are different globs, but `replay-*` would match both - and a
        // join handed six streams of two different fixtures reports `EXIT_UNUSABLE` rather than a
        // verdict, which reads as a broken runner rather than as a mistake in this file.
        val prPattern = stepValue("Download every leg's digest", "pattern")
        val nightlyPattern = stepValue("Download every nightly leg's digest", "pattern")
        val prName = stepValue("Upload this leg's digest stream", "name")
        val nightlyName = stepValue("Upload this nightly leg's digest stream", "name")

        assertTrue(
            globMatches(prPattern, prName) && globMatches(nightlyPattern, nightlyName),
            "a join's download pattern does not match its own legs' artifact name: " +
                "'$prPattern' against '$prName', '$nightlyPattern' against '$nightlyName'",
        )
        assertTrue(
            !globMatches(prPattern, nightlyName),
            "the PR join would download the nightly legs' digests as well ('$prPattern' matches " +
                "'$nightlyName'), and a join handed two fixtures cannot produce a verdict",
        )
        assertTrue(
            !globMatches(nightlyPattern, prName),
            "the nightly join would download the PR legs' digests as well",
        )
    }

    /**
     * The value the `ci.yml` job called [job] hands `-Pudea.replay.<name>`.
     *
     * Scoped to one job rather than to the whole file. Issue #165 added a second pair of jobs
     * replaying a second fixture, so `-Pudea.replay.out=` and `-Pudea.replay.streams=` each now
     * appear twice - and a fence that counted them across the file would have compared the PR
     * job's path against whichever of the two the regex found first. That is the shape of
     * check that runs and returns a true answer about the wrong subject.
     */
    private fun gradlePropertyInJob(job: String, name: String): String {
        val found = Regex("-Pudea\\.replay\\.$name=(.+)").findAll(jobBlock(job))
            .map { it.groupValues[1].trim() }.toList()
        assertEquals(
            1, found.size,
            "the ci.yml job '$job' should hand -Pudea.replay.$name to exactly one step; found $found",
        )
        return substituteExpressions(found.single())
    }

    /**
     * The `ci.yml` block of the job called [job], from its header to the next job's.
     *
     * A job header is the only thing in this file at exactly two spaces of indent followed by a
     * name and a colon, which is what the pattern anchors on. It has to anchor on the *whole*
     * name too: `replay-equality` is a prefix of `replay-equality-nightly`, and a slicer that
     * matched a prefix would hand every assertion the wrong job's steps while looking right.
     * `the job slicer cuts one job and not its neighbour` is the control for it.
     */
    private fun jobBlock(job: String): String {
        val header = Regex("(?m)^ {2}${Regex.escape(job)}:\\s*$").find(workflowCode)
            ?: fail("ci.yml has no job called '$job'")
        val rest = workflowCode.substring(header.range.last + 1)
        val next = Regex("(?m)^ {2}[A-Za-z][\\w-]*:\\s*$").find(rest)
        return rest.substring(0, next?.range?.first ?: rest.length)
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

    /** The `ci.yml` jobs this class reads, named once so a rename is one edit. */
    private companion object {
        const val PR_JOB: String = "replay-equality"
        const val PR_JOIN_JOB: String = "replay-equality-join"
        const val NIGHTLY_JOB: String = "replay-equality-nightly"
        const val NIGHTLY_JOIN_JOB: String = "replay-equality-nightly-join"
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
