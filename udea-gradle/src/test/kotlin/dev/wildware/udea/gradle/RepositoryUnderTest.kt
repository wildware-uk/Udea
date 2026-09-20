package dev.wildware.udea.gradle

import java.io.File

/**
 * The repository this module's source fences read, as the test task hands it over.
 *
 * Several tests here assert about files Gradle compiles nothing from - the root build script, the
 * CI workflow, `build-logic`'s build script - and a test that guessed the tree from `user.dir`
 * would read a different one under Gradle and under an IDE. A source fence pointed at the wrong
 * tree passes over nothing, so the root arrives as a system property or the test fails saying so.
 *
 * Extracted from `LatencyBudgetAggregate`, which was the first fence to need it, when
 * `BuildLogicGateTest` became the second.
 */
internal object RepositoryUnderTest {

    /** The system property `:udea-gradle:test` sets; spelt the same in the build script. */
    const val PROPERTY: String = "udea.repoRoot"

    val root: File
        get() = File(
            checkNotNull(System.getProperty(PROPERTY)) {
                "$PROPERTY is not set; the test task must pass the repository root"
            },
        )

    /** A repository-relative path, resolved against [root]. */
    fun file(path: String): File = File(root, path)
}
