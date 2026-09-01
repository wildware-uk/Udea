package dev.wildware.udea.gradle.ci

import java.io.File

/**
 * The root build script's `latencyBudgetTasks`, read out of the file rather than restated here.
 *
 * Both of this package's latency tests need the same list, and a second copy of it is a second
 * thing to keep in step: the whole point of issue #175's arrangement is that adding a budget to
 * the aggregate puts it under every gate at once. Extracted from `LatencyBudgetJobTest` when
 * issue #182 added a second reader.
 */
internal object LatencyBudgetAggregate {

    /** The declaration in `build.gradle.kts` the membership is read out of. */
    const val MEMBER_LIST: String = "val latencyBudgetTasks = listOf("

    private val MEMBER = Regex("\"(:[A-Za-z0-9:_-]+)\"")

    /**
     * The repository root, as the test task hands it over.
     *
     * A test that guessed it from `user.dir` would read a different tree under Gradle and under
     * an IDE, and a source fence pointed at the wrong tree passes over nothing.
     */
    val repoRoot: File
        get() = File(
            checkNotNull(System.getProperty("udea.repoRoot")) {
                "udea.repoRoot is not set; the test task must pass the repository root"
            },
        )

    val rootBuildScript: File get() = File(repoRoot, "build.gradle.kts")

    /**
     * The task paths hung on `udeaLatencyBudgets`.
     *
     * `//` tails are stripped first: a member named only in a comment is not a member, and a
     * slicer that reads raw lines is how a source-reading fence gets defeated.
     *
     * @throws IllegalStateException if the declaration is missing or parses to nothing, because a
     *   budget list that reads as empty makes every assertion built on it vacuous.
     */
    fun members(): List<String> {
        val script = rootBuildScript.readText()
        val begin = script.indexOf(MEMBER_LIST)
        check(begin >= 0) {
            "build.gradle.kts no longer declares `$MEMBER_LIST`, so nothing can tell which tasks " +
                "the latency job is responsible for"
        }
        val body = script.substring(begin + MEMBER_LIST.length).substringBefore("\n)")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        val members = MEMBER.findAll(body).map { it.groupValues[1] }.toList()
        check(members.isNotEmpty()) {
            "`$MEMBER_LIST` parsed to no task paths. A budget list that reads as empty makes " +
                "every assertion built on it vacuous."
        }
        return members
    }
}
