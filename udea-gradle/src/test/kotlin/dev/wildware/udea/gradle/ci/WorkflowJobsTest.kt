package dev.wildware.udea.gradle.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The reader [LatencyBudgetJobTest] stands on, exercised against fixtures rather than against the
 * repository's own workflow.
 *
 * Both directions are run for every property that matters, because a fence that only ever answers
 * "yes" has not been shown to be a fence. The pair that earns its place is the comment control:
 * the same `./gradlew udeaLatencyBudgets` line is read once as a step and once as a comment, and
 * the reader has to disagree with itself about them.
 */
class WorkflowJobsTest {

    @Test
    fun `a job's steps are read one entry per run step`() {
        val jobs = WorkflowJobs.parse(WORKFLOW)
        val budgets = jobs.single { it.id == "latency-budgets" }
        // A block scalar keeps its line breaks: the reader joins with newlines rather than
        // folding, because every consumer of this text searches it or splits it on whitespace,
        // and folding a `>-` correctly would be work that changes no answer.
        assertEquals(
            listOf("chmod +x ./gradlew", "./gradlew udeaLatencyBudgets\n--no-parallel"),
            budgets.steps,
        )
    }

    @Test
    fun `a task named only in a comment is not read as a step`() {
        val jobs = WorkflowJobs.parse(WORKFLOW)
        val build = jobs.single { it.id == "build" }
        // The fixture's `build` job carries `# ./gradlew udeaLatencyBudgets` as a shell comment
        // and `# udeaLatencyBudgets used to run here` as a YAML comment. Neither is a step.
        assertTrue(
            build.steps.none { "udeaLatencyBudgets" in it },
            "a commented-out invocation was read as one that runs: ${build.steps}",
        )
        // The control for the control: the same text, uncommented, IS read. Without this line the
        // assertion above would pass just as happily against a reader that finds nothing at all.
        assertTrue(
            jobs.single { it.id == "latency-budgets" }.steps.any { "udeaLatencyBudgets" in it },
            "the reader found no invocation anywhere, so the comment case proved nothing",
        )
    }

    @Test
    fun `a matrix runs-on resolves through the job's own os list`() {
        val jobs = WorkflowJobs.parse(WORKFLOW)
        assertEquals(listOf("ubuntu-latest", "windows-latest"), jobs.single { it.id == "build" }.runsOn)
        assertEquals(
            listOf("ubuntu-latest", "windows-latest"),
            jobs.single { it.id == "latency-budgets" }.runsOn,
        )
        assertEquals(listOf("ubuntu-latest"), jobs.single { it.id == "docs" }.runsOn)
    }

    @Test
    fun `a block scalar step ends at the next step rather than running into it`() {
        val budgets = WorkflowJobs.parse(WORKFLOW).single { it.id == "latency-budgets" }
        assertTrue(
            budgets.steps.none { "chmod" in it && "udeaLatencyBudgets" in it },
            "two steps were joined into one: ${budgets.steps}",
        )
    }

    @Test
    fun `a workflow with no jobs is refused rather than read as having none`() {
        assertFailsWith<IllegalArgumentException> { WorkflowJobs.parse("name: CI\non:\n  push:\n") }
        assertFailsWith<IllegalArgumentException> { WorkflowJobs.parse("name: CI\njobs:\n") }
    }

    private companion object {
        /**
         * The shapes this repository's workflow actually uses: an inline `run:`, a folded block
         * `run: >-`, a literal block `run: |`, a matrix `runs-on`, a plain `runs-on`, a YAML
         * comment and a shell comment.
         */
        val WORKFLOW = """
            name: CI

            on:
              push:

            jobs:
              build:
                name: build (${'$'}{{ matrix.os }})
                runs-on: ${'$'}{{ matrix.os }}
                strategy:
                  matrix:
                    os: [ubuntu-latest, windows-latest]
                steps:
                  # udeaLatencyBudgets used to run here, and no longer does.
                  - name: Build
                    run: |
                      set -euo pipefail
                      # ./gradlew udeaLatencyBudgets
                      ./gradlew build --stacktrace

              latency-budgets:
                runs-on: ${'$'}{{ matrix.os }}
                strategy:
                  matrix:
                    os:
                      - ubuntu-latest
                      - windows-latest
                steps:
                  - name: Wrapper
                    run: chmod +x ./gradlew
                  - name: Measure
                    run: >-
                      ./gradlew udeaLatencyBudgets
                      --no-parallel

              docs:
                runs-on: ubuntu-latest
                steps:
                  - name: Nothing
                    run: echo hello
        """.trimIndent()
    }
}
