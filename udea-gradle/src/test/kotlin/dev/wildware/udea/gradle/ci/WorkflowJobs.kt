package dev.wildware.udea.gradle.ci

/**
 * One job of a GitHub Actions workflow, read far enough to answer the one question this
 * repository asks of `.github/workflows/ci.yml` from a test: **which job runs a given Gradle
 * task, on which runners, and what else does the same invocation run.**
 *
 * @property id the job's key under `jobs:`, for example `latency-budgets`.
 * @property runsOn every runner image the job executes on. A `runs-on: ${'$'}{{ matrix.os }}` is
 *   resolved through the job's own `strategy.matrix.os` list, so a matrix leg and a plain
 *   `runs-on:` answer the same question in the same shape.
 * @property steps the text of each `run:` step, in order, one entry per step. Per step and not
 *   per job on purpose: "this invocation runs nothing but the budgets" is a claim about a single
 *   `./gradlew` line, and a job-wide join would let a warm-up step launder a `build` into it.
 */
data class WorkflowJob(
    val id: String,
    val runsOn: List<String>,
    val steps: List<String>,
)

/**
 * A deliberately small reader for the shape of `ci.yml` that a gate needs, and no more.
 *
 * ## Why comments are removed first, and why that is the load-bearing part
 *
 * A workflow gate that greps the raw file passes on prose: a job deleted but left behind in a
 * comment, or a `# ./gradlew udeaLatencyBudgets` inside a shell block, would satisfy it. So every
 * line whose first non-blank character is `#` is dropped before anything else is read - YAML
 * comments and shell comments inside a `run:` block alike, which is the right answer for both,
 * because a task named in a comment is a task nobody runs. `WorkflowJobsTest` runs that control
 * explicitly, in both directions.
 *
 * ## What it does not do
 *
 * It is not a YAML parser. It does not resolve anchors, flow mappings, quoted keys, or a matrix
 * `include`/`exclude`, and it reads a `#` at the start of a line as a comment even inside a
 * quoted scalar. It is enough for this repository's own workflow, and it refuses loudly - no
 * `jobs:` key, or a `jobs:` block with no jobs in it - rather than returning an empty list, which
 * is the failure mode that would make every assertion built on it vacuous.
 */
object WorkflowJobs {

    /** Every job of [yaml], in file order. */
    fun parse(yaml: String): List<WorkflowJob> {
        val lines = yaml.lines().map { if (it.isCommentLine()) "" else it }
        val jobsAt = lines.indexOfFirst { it.startsWith("jobs:") }
        require(jobsAt >= 0) { "the workflow has no top-level `jobs:` key" }

        val body = lines.subList(jobsAt + 1, lines.size)
            .takeWhile { it.isBlank() || it.indent() > 0 }

        val headers = body.indices.filter { at -> body[at].isJobHeader() }
        require(headers.isNotEmpty()) { "the workflow's `jobs:` block declares no jobs" }

        return headers.mapIndexed { which, at ->
            val end = headers.getOrElse(which + 1) { body.size }
            val jobLines = body.subList(at + 1, end)
            WorkflowJob(
                id = body[at].trim().removeSuffix(":"),
                runsOn = runsOn(jobLines),
                steps = runSteps(jobLines),
            )
        }
    }

    /**
     * The runner images the job executes on.
     *
     * A `runs-on:` naming an expression is resolved through `matrix.os`; anything else is taken
     * literally. An expression that resolves to nothing yields an empty list rather than the
     * expression text, so a caller comparing OS coverage compares images or compares nothing.
     */
    private fun runsOn(jobLines: List<String>): List<String> {
        val declared = jobLines.firstOrNull { it.trim().startsWith("runs-on:") }
            ?.substringAfter("runs-on:")?.trim().orEmpty()
        if (!declared.contains("\${{")) return listOfNotNull(declared.ifBlank { null })

        val osAt = jobLines.indexOfFirst { it.trim().startsWith("os:") }
        if (osAt < 0) return emptyList()
        val inline = jobLines[osAt].substringAfter("os:").trim()
        if (inline.startsWith("[")) {
            return inline.trim('[', ']').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val indent = jobLines[osAt].indent()
        return jobLines.subList(osAt + 1, jobLines.size)
            .takeWhile { it.isBlank() || it.indent() > indent }
            .filter { it.trim().startsWith("- ") }
            .map { it.trim().removePrefix("- ").trim() }
    }

    /** The text of every `run:` step, inline or block scalar, in order. */
    private fun runSteps(jobLines: List<String>): List<String> = jobLines.indices
        .filter { jobLines[it].trim().startsWith("run:") }
        .map { at -> scalarAt(jobLines, at) }

    /**
     * The scalar belonging to the `run:` key on line [at].
     *
     * An inline `run: ./gradlew build` is the text after the colon. A block introducer (`|`, `>-`
     * and the rest) takes every following line indented deeper than the key, which is the YAML
     * rule and is also what makes a step's text end at the next step rather than running on into
     * it.
     */
    private fun scalarAt(lines: List<String>, at: Int): String {
        val inline = lines[at].substringAfter("run:").trim()
        if (inline.isNotEmpty() && !inline.startsWith(">") && !inline.startsWith("|")) return inline
        val indent = lines[at].indent()
        return lines.subList(at + 1, lines.size)
            .takeWhile { it.isBlank() || it.indent() > indent }
            .joinToString("\n") { it.trim() }
            .trim()
    }

    private fun String.isCommentLine(): Boolean = trimStart().startsWith("#")

    /** The column of the first non-blank character, or [Int.MAX_VALUE] for a blank line. */
    private fun String.indent(): Int =
        indexOfFirst { !it.isWhitespace() }.let { if (it < 0) Int.MAX_VALUE else it }

    private fun String.isJobHeader(): Boolean {
        if (indent() != 2) return false
        val key = trim()
        if (!key.endsWith(":")) return false
        val name = key.dropLast(1)
        return name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
