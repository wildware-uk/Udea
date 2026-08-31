package dev.wildware.udea.gradle.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #175: **a wall-clock latency measurement taken beside a parallel build measures the
 * build**, so the repository's latency gates have to be measured by a CI job that has the runner
 * to itself.
 *
 * ## What was wrong, and what this asserts instead
 *
 * `udeaPhase2Exit`, `udeaDaemonBudget`, the graph-deserialisation budget and the two `udea-core`
 * benches used to hang off `check`, which meant every one of them was measured inside
 * `./gradlew build` with nineteen other modules compiling on the same cores. They failed on
 * `ubuntu-latest` and on `windows-latest` for that reason and for no other: on this repository's
 * own box the same code medians 195ms alone and 646ms inside a full build. Three waves of
 * developers each rediscovered it by re-running the task solo.
 *
 * They now hang off the root's `udeaLatencyBudgets` aggregate instead, and `ci.yml` measures that
 * aggregate in a job of its own. This test is what stops the two halves drifting apart, because
 * the second half lives in a file Gradle never reads: it asserts, against the real workflow, that
 * every runner the `build` job covers also gets a latency job, that the measuring invocation runs
 * serially, and that nothing else in the workflow runs a budget task beside anything.
 *
 * ## What it does not say
 *
 * It does not assert that no budget task has been wired back onto `check` - that is a Gradle
 * question and this test reads a YAML file. It does not need to: a budget back on `check` is
 * measured inside `build` again and goes red on the runner, which is the loud failure this whole
 * ticket is about rather than a silent one.
 */
class LatencyBudgetJobTest {

    @Test
    fun `every runner the build job covers has a latency job of its own`() {
        val jobs = WorkflowJobs.parse(workflow.readText())
        val measuring = jobs.filter { job -> job.steps.any { AGGREGATE in it } }
        assertTrue(
            measuring.isNotEmpty(),
            "no job in ${workflow.name} runs `$AGGREGATE`. The latency budgets are then measured " +
                "nowhere, which is issue #175's option 3 - the one the issue ranks last.",
        )

        val build = jobs.single { it.id == "build" }
        val covered = measuring.flatMap { it.runsOn }.toSet()
        assertTrue(
            covered.containsAll(build.runsOn),
            "the `build` job runs on ${build.runsOn} but the latency budgets are only measured on " +
                "$covered. A budget nobody measures on a runner is a budget that runner can " +
                "regress silently.",
        )
    }

    @Test
    fun `the measuring invocation has the runner to itself`() {
        val steps = WorkflowJobs.parse(workflow.readText())
            .flatMap { it.steps }
            .filter { AGGREGATE in it }
        assertTrue(steps.isNotEmpty(), "no `$AGGREGATE` invocation to check; see the test above")

        steps.forEach { step ->
            SERIAL_FLAGS.forEach { flag ->
                assertTrue(
                    flag in step,
                    "the invocation that measures the latency budgets is missing `$flag`, so " +
                        "Gradle may run other tasks on the same cores while it measures. That is " +
                        "the defect issue #175 describes, moved into the job that was supposed to " +
                        "fix it. The step was:\n$step",
                )
            }
            val alsoRuns = step.gradleTaskTokens().filter { it in LIFECYCLE_TASKS }
            assertTrue(
                alsoRuns.isEmpty(),
                "the measuring invocation also runs $alsoRuns, so it compiles the repository while " +
                    "it times it. Warm the build in a step of its own and leave this one to the " +
                    "measurement. The step was:\n$step",
            )
        }
    }

    @Test
    fun `no other step in the workflow runs a budget task beside anything else`() {
        val members = aggregateMembers()
        val offenders = WorkflowJobs.parse(workflow.readText())
            .flatMap { job -> job.steps.map { job.id to it } }
            .filter { (_, step) -> AGGREGATE !in step }
            .flatMap { (id, step) ->
                members.filter { member -> member.substringAfterLast(':') in step }
                    .map { "$id runs ${it.substringAfterLast(':')}" }
            }
        assertTrue(
            offenders.isEmpty(),
            "a latency budget is invoked outside the job that measures it serially: $offenders. " +
                "Every member of `$AGGREGATE` is a wall-clock measurement and belongs in the " +
                "job that has the runner to itself.",
        )
    }

    @Test
    fun `a latency budget is never up to date and never served from the build cache`() {
        // Observed, not theorised. Actions run 33451573256 was a docs-only commit on this branch,
        // so every budget task had identical inputs, and both runners reported all six
        // `FROM-CACHE` and finished in seconds. Two green ticks and no measurement between them -
        // the same shape as a skipped test reported as a pass, which is the defect this
        // repository has already closed for the GL tests and for the atlas tests.
        val script = File(repoRoot, "build.gradle.kts").readText()
            .lines().joinToString("\n") { it.substringBefore("//") }
        val at = script.indexOf(CACHE_GUARD)
        assertTrue(
            at >= 0,
            "build.gradle.kts no longer configures the latency budgets with `$CACHE_GUARD`. " +
                "A `Test` task is cacheable by default, so without it Gradle answers a stopwatch " +
                "from another machine's recorded time.",
        )
        assertTrue(
            script.indexOf("outputs.upToDateWhen { false }") >= 0,
            "the latency budgets are cache-disabled but still up-to-date-able, so a second run " +
                "on the same machine reports the first run's numbers without measuring",
        )
    }

    /**
     * The task paths the root build script hangs on `udeaLatencyBudgets`.
     *
     * Read out of `build.gradle.kts` rather than listed here, so adding a budget to the aggregate
     * also puts it under this test without anybody remembering to. `//` tails are stripped first:
     * a member named only in a comment is not a member, and a slicer that reads raw lines is how
     * a source-reading fence gets defeated. An empty or missing list fails rather than passing
     * over nothing.
     */
    private fun aggregateMembers(): List<String> {
        val script = File(repoRoot, "build.gradle.kts").readText()
        val begin = script.indexOf(MEMBER_LIST)
        assertTrue(
            begin >= 0,
            "build.gradle.kts no longer declares `$MEMBER_LIST`, so this test cannot tell which " +
                "tasks the latency job is responsible for",
        )
        val body = script.substring(begin + MEMBER_LIST.length).substringBefore("\n)")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        val members = MEMBER.findAll(body).map { it.groupValues[1] }.toList()
        assertTrue(
            members.isNotEmpty(),
            "`$MEMBER_LIST` parsed to no task paths. A budget list that reads as empty makes " +
                "every assertion built on it vacuous.",
        )
        return members
    }

    /**
     * The words of [this] that Gradle would read as task names: not flags, not the wrapper, and
     * not the value half of a `-P` property.
     */
    private fun String.gradleTaskTokens(): List<String> = split(Regex("\\s+"))
        .filter { it.isNotBlank() && !it.startsWith("-") && !it.endsWith("gradlew") }

    private val repoRoot: File
        get() = File(
            checkNotNull(System.getProperty("udea.repoRoot")) {
                "udea.repoRoot is not set; the test task must pass the repository root"
            },
        )

    private val workflow: File get() = File(repoRoot, ".github/workflows/ci.yml")

    private companion object {
        /** The root aggregate every latency measurement hangs off. */
        const val AGGREGATE = "udeaLatencyBudgets"

        /** The declaration in `build.gradle.kts` this test reads the membership out of. */
        const val MEMBER_LIST = "val latencyBudgetTasks = listOf("

        /** What turns the build cache off for a measurement, in the root build script. */
        const val CACHE_GUARD = "outputs.cacheIf("

        val MEMBER = Regex("\"(:[A-Za-z0-9:_-]+)\"")

        /**
         * What makes the invocation exclusive. `--no-parallel` stops Gradle running project tasks
         * concurrently; `--max-workers=1` stops it running anything else beside the forked test
         * JVM. Either alone leaves a second task on the other cores.
         */
        val SERIAL_FLAGS = listOf("--no-parallel", "--max-workers=1")

        /** Task names whose presence in the measuring invocation would make it a build again. */
        val LIFECYCLE_TASKS = setOf("build", "check", "assemble", "test")
    }
}
