package dev.wildware.udea.build

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `udeaVerifyNoLegacyDependencies`, run against a build that really violates it.
 *
 * The rule itself is decided in [LegacyDependencyRules] and tested there. What these prove is
 * the part a unit test cannot: that the task is attached to a project, resolves the
 * configurations it claims to, and puts a diagnosable message in front of whoever broke it.
 * A gate nobody has watched fail is not a gate.
 */
class LegacyDependencyCheckTest {

    private val gate = "udea.legacy-dependency-check"

    @Test
    fun `a direct dependency on common fails and names project, configuration and coordinate`(
        @TempDir root: File,
    ) {
        val fixture = GradleFixture(root)
            .project("common", "plugins { `java-library` }")
            .project(
                "udea-core",
                gatedProject(gate, "dependencies { implementation(project(\":common\")) }"),
            )

        val result = fixture.buildAndFail(":udea-core:udeaVerifyNoLegacyDependencies")

        assertTrue("UDEA-LEGACY-001" in result.output, result.output)
        assertTrue(":udea-core" in result.output, result.output)
        assertTrue(":common" in result.output, result.output)
        assertTrue("compileClasspath" in result.output, result.output)
    }

    @Test
    fun `a dependency two hops away fails and the message prints the resolution path`(
        @TempDir root: File,
    ) {
        // Nothing in :udea-gas's build file mentions :common. This is the case the whole
        // resolved-graph approach exists for, and the case a declared-dependency scan misses.
        val fixture = GradleFixture(root)
            .project("common", "plugins { `java-library` }")
            .project(
                "udea-core",
                "plugins { `java-library` }\ndependencies { api(project(\":common\")) }",
            )
            .project(
                "udea-gas",
                gatedProject(gate, "dependencies { implementation(project(\":udea-core\")) }"),
            )

        val result = fixture.buildAndFail(":udea-gas:udeaVerifyNoLegacyDependencies")

        assertTrue(
            ":udea-gas -> :udea-core -> :common" in result.output,
            "the failure must print the whole path, not just the offender:\n${result.output}",
        )
    }

    @Test
    fun `a clean project passes`(@TempDir root: File) {
        val fixture = GradleFixture(root)
            .project("common", "plugins { `java-library` }")
            .project(
                "udea-core",
                gatedProject(gate, "dependencies { api(project(\":udea-annotations\")) }"),
            )
            .project("udea-annotations", "plugins { `java-library` }")

        val result = fixture.build(":udea-core:udeaVerifyNoLegacyDependencies")

        assertTrue(
            result.output.contains("BUILD SUCCESSFUL") || result.task(":udea-core:udeaVerifyNoLegacyDependencies") != null,
            result.output,
        )
    }

    @Test
    fun `the gate is reachable from check, so a plain build cannot skip it`(@TempDir root: File) {
        val fixture = GradleFixture(root)
            .project("common", "plugins { `java-library` }")
            .project(
                "udea-core",
                gatedProject(gate, "dependencies { implementation(project(\":common\")) }"),
            )

        val result = fixture.buildAndFail(":udea-core:check")

        assertTrue("UDEA-LEGACY-001" in result.output, result.output)
    }

    @Test
    fun `the gate is configuration-cache compatible`(@TempDir root: File) {
        // The project has the configuration cache on, so a task that captured a Project at
        // execution time would fail the build rather than the rule. Running twice is what
        // proves the cached entry is reusable rather than merely storable.
        val fixture = GradleFixture(root)
            .project("udea-net", gatedProject(gate, ""))

        fixture.build(":udea-net:udeaVerifyNoLegacyDependencies")
        val second = fixture.build(":udea-net:udeaVerifyNoLegacyDependencies")

        assertTrue("Configuration cache entry reused" in second.output, second.output)
    }

    @Test
    fun `a project with none of the scanned classpaths fails rather than passing vacuously`(
        @TempDir root: File,
    ) {
        // No `java` plugin means no compileClasspath, no runtimeClasspath and nothing to
        // inspect. A gate that reports green on an empty input is the failure this branch
        // exists to make impossible.
        val fixture = GradleFixture(root)
            .project("udea-agent", "plugins { id(\"$gate\") }")

        val result = fixture.buildAndFail(":udea-agent:udeaVerifyNoLegacyDependencies")

        assertTrue("inspected nothing" in result.output, result.output)
    }
}
