package dev.wildware.udea.gradle.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #181: the `clean-build-budget` job judges a commit **against its base, timed on the same
 * runner**, and never against an absolute number of milliseconds.
 *
 * The rule itself - fastest sample of each side, and the tolerance - is `CleanBuildComparison` in
 * `build-logic`, and its own test executes it. What nothing there can see is the workflow, which
 * is the half that decides whether the rule is ever reached: a job that timed only the head, or
 * went back to comparing one build against a constant, would leave that test green while the gate
 * returned to being a verdict about the machine. This reads the real `ci.yml` and asserts the
 * shape the verdict depends on.
 */
class CleanBuildBudgetJobTest {

    private val job: WorkflowJob by lazy {
        val jobs = WorkflowJobs.parse(File(LatencyBudgetAggregate.repoRoot, ".github/workflows/ci.yml").readText())
        val found = jobs.filter { it.id == JOB }
        assertTrue(found.size == 1, "expected one `$JOB` job in ci.yml, found ${found.size}")
        found.single()
    }

    @Test
    fun `the job times clean builds of both the base checkout and the head`() {
        val timing = job.steps.filter { CLEAN_BUILD in it }
        assertTrue(
            timing.isNotEmpty(),
            "no step of `$JOB` runs `$CLEAN_BUILD`, so the job measures nothing a clean build costs",
        )
        timing.forEach { step ->
            listOf(BASE_CHECKOUT, BASE_ROW, HEAD_ROW).forEach { needed ->
                assertTrue(
                    needed in step,
                    "the step that times `$CLEAN_BUILD` does not mention `$needed`, so it no longer " +
                        "times the base and the head side by side on one runner - which is the only " +
                        "thing that takes the runner's speed out of the verdict. The step was:\n$step",
                )
            }
        }
    }

    @Test
    fun `the verdict is the comparison task, run after the samples are taken`() {
        val steps = job.steps
        val timingAt = steps.indexOfFirst { CLEAN_BUILD in it }
        val verdictAt = steps.indexOfFirst { VERDICT_TASK in it && SAMPLES_PROPERTY in it }
        assertTrue(
            verdictAt >= 0,
            "no step of `$JOB` runs `$VERDICT_TASK` with `$SAMPLES_PROPERTY`, so whatever the job " +
                "decides is decided outside the rule `CleanBuildComparisonTest` executes",
        )
        assertTrue(
            timingAt in 0 until verdictAt,
            "`$VERDICT_TASK` runs at step $verdictAt but the samples are timed at step $timingAt; " +
                "it has to judge samples this run took, not a file left behind by an earlier one",
        )
    }

    private companion object {
        const val JOB = "clean-build-budget"
        const val CLEAN_BUILD = "clean udeaAssemble --no-build-cache"
        const val BASE_CHECKOUT = "UDEA_CLEAN_BUILD_BASE"
        const val BASE_ROW = "echo \"base "
        const val HEAD_ROW = "echo \"head "
        const val VERDICT_TASK = "udeaCleanBuildVerdict"
        const val SAMPLES_PROPERTY = "-Pudea.cleanBuild.samples="
    }
}
