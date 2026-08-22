package dev.wildware.udea.build

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `udeaVerifyRelease`, run against builds that really package an agent class.
 *
 * The gate exists because the exclusion it enforces depends on a Gradle variant someone can
 * misconfigure, and a misconfigured variant fails silently. These are the runs that prove the
 * gate is louder than the misconfiguration.
 */
class VerifyReleaseTest {

    private val gate = "udea.release-check"
    private val agentEntry = "dev/wildware/udea/agent/AgentTools.class"

    private fun moba(fixture: GradleFixture, dependencies: String = ""): GradleFixture =
        fixture.project(
            "moba",
            gatedProject(
                gate,
                """
                $dependencies
                ${fixture.jarFrom()}
                """.trimIndent(),
            ),
        )

    @Test
    fun `an agent class in the packaged jar fails a release build`(@TempDir root: File) {
        val fixture = GradleFixture(root)
        moba(fixture).packagedFile("moba", agentEntry)

        val result = fixture.buildAndFail(":moba:assemble", "-Pudea.release=true")

        assertTrue("UDEA-REL-001" in result.output, result.output)
        assertTrue(agentEntry in result.output, result.output)
        assertTrue("moba.jar" in result.output, "the failure must name the jar:\n${result.output}")
    }

    @Test
    fun `the scan reads the zip, not the classpath - nothing here depends on the agent`(
        @TempDir root: File,
    ) {
        // The class is in the jar and in no configuration at all. A model-only check reports
        // this build clean, which is precisely the failure mode udeaVerifyRelease exists for.
        val fixture = GradleFixture(root)
        moba(fixture).packagedFile("moba", agentEntry)

        val result = fixture.buildAndFail(":moba:udeaVerifyRelease", "-Pudea.release=true")

        assertTrue("UDEA-REL-001" in result.output, result.output)
        assertFalse(
            "UDEA-REL-002" in result.output,
            "no configuration references the agent, so only the artifact rule may fire:\n${result.output}",
        )
    }

    @Test
    fun `a clean jar passes a release build`(@TempDir root: File) {
        val fixture = GradleFixture(root)
        moba(fixture).packagedFile("moba", "dev/wildware/udea/moba/Main.class")

        val result = fixture.build(":moba:assemble", "-Pudea.release=true")

        assertEquals(TaskOutcome.SUCCESS, result.task(":moba:udeaVerifyRelease")?.outcome, result.output)
    }

    @Test
    fun `a non-release assemble does not fail with the agent host present`(@TempDir root: File) {
        // A development build is supposed to carry the agent surface. A gate that failed here
        // would be a gate people learn to route around.
        val fixture = GradleFixture(root)
            .project("udea-agent-host", "plugins { `java-library` }")
        moba(fixture, "dependencies { implementation(project(\":udea-agent-host\")) }")
            .packagedFile("moba", agentEntry)

        val result = fixture.build(":moba:assemble")

        assertEquals(TaskOutcome.SKIPPED, result.task(":moba:udeaVerifyRelease")?.outcome, result.output)
    }

    @Test
    fun `the agent host on the release runtime classpath fails the model half`(@TempDir root: File) {
        val fixture = GradleFixture(root)
            .project("udea-agent-host", "plugins { `java-library` }")
        moba(fixture, "dependencies { implementation(project(\":udea-agent-host\")) }")

        val result = fixture.buildAndFail(":moba:udeaVerifyRelease", "-Pudea.release=true")

        assertTrue("UDEA-REL-002" in result.output, result.output)
        assertTrue(":udea-agent-host" in result.output, result.output)
        assertTrue("runtimeClasspath" in result.output, result.output)
    }

    @Test
    fun `assemble is finalized by the gate, so it cannot be forgotten`(@TempDir root: File) {
        val fixture = GradleFixture(root)
        moba(fixture)

        val result = fixture.build(":moba:assemble", "-Pudea.release=true", "--dry-run")

        assertTrue(":moba:udeaVerifyRelease SKIPPED" in result.output, result.output)
    }

    @Test
    fun `finding no archive at all fails rather than passing`(@TempDir root: File) {
        // No `java` plugin means no jar. A release gate that reports green because it found
        // nothing to look at is the same silent failure wearing a different hat.
        val fixture = GradleFixture(root)
            .project("moba", "plugins { id(\"$gate\") }")

        val result = fixture.buildAndFail(":moba:udeaVerifyRelease", "-Pudea.release=true")

        assertTrue("no packaged artifact" in result.output, result.output)
        assertTrue("UDEA-REL-001" in result.output, result.output)
    }

    @Test
    fun `the banned prefix list is configurable per project`(@TempDir root: File) {
        val fixture = GradleFixture(root)
        moba(
            fixture,
            "tasks.named<dev.wildware.udea.build.UdeaVerifyReleaseTask>(\"udeaVerifyRelease\") " +
                "{ bannedPrefixes.set(listOf(\"dev/wildware/udea/leveleditor/\")) }",
        ).packagedFile("moba", "dev/wildware/udea/leveleditor/Editor.class")
            .packagedFile("moba", agentEntry)

        val result = fixture.buildAndFail(":moba:udeaVerifyRelease", "-Pudea.release=true")

        assertTrue("dev/wildware/udea/leveleditor/Editor.class" in result.output, result.output)
        assertFalse(
            agentEntry in result.output,
            "an overridden prefix list replaces the default rather than adding to it:\n${result.output}",
        )
    }
}
