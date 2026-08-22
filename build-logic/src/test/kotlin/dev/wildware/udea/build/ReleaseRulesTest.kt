package dev.wildware.udea.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What `udeaVerifyRelease` decides, without needing a jar to decide it about. */
class ReleaseRulesTest {

    private val jar = "/build/libs/moba.jar"

    private fun entries(vararg names: String) =
        names.map { ReleaseRules.ArchiveEntry(jar, it) }

    @Test
    fun `an agent class in the artifact is a violation`() {
        val violations = ReleaseRules.artifactViolations(
            entries("dev/wildware/udea/agent/AgentTools.class", "dev/wildware/udea/moba/Main.class"),
            ReleaseRules.DEFAULT_BANNED_PREFIXES,
        )
        assertEquals(listOf("dev/wildware/udea/agent/AgentTools.class"), violations.map { it.entryName })
    }

    @Test
    fun `the agent host package is banned as well as the agent package`() {
        val violations = ReleaseRules.artifactViolations(
            entries("dev/wildware/udea/agenthost/HttpServer.class"),
            ReleaseRules.DEFAULT_BANNED_PREFIXES,
        )
        assertEquals(1, violations.size)
    }

    @Test
    fun `a package that merely starts with the same letters is not banned`() {
        // `dev/wildware/udea/agentless/` is not `dev/wildware/udea/agent/`. A prefix match on
        // the package name without the trailing slash would fail an innocent module.
        assertTrue(
            ReleaseRules.artifactViolations(
                entries("dev/wildware/udea/agentless/Thing.class"),
                ReleaseRules.DEFAULT_BANNED_PREFIXES,
            ).isEmpty(),
        )
    }

    @Test
    fun `a clean artifact produces no report`() {
        val violations = ReleaseRules.artifactViolations(
            entries("dev/wildware/udea/moba/Main.class", "META-INF/MANIFEST.MF"),
            ReleaseRules.DEFAULT_BANNED_PREFIXES,
        )
        assertNull(ReleaseRules.report(":moba", violations, ReleaseRules.DEFAULT_BANNED_PREFIXES))
    }

    @Test
    fun `the report names the rule id, the jar path and the offending entry`() {
        val report = assertNotNull(
            ReleaseRules.report(
                ":moba",
                ReleaseRules.artifactViolations(
                    entries("dev/wildware/udea/agent/AgentTools.class"),
                    ReleaseRules.DEFAULT_BANNED_PREFIXES,
                ),
                ReleaseRules.DEFAULT_BANNED_PREFIXES,
            ),
        )
        assertTrue("UDEA-REL-001" in report, report)
        assertTrue(jar in report, report)
        assertTrue("dev/wildware/udea/agent/AgentTools.class" in report, report)
        assertTrue(":moba" in report, report)
    }

    @Test
    fun `the banned prefix list is configurable`() {
        val violations = ReleaseRules.artifactViolations(
            entries("dev/wildware/udea/leveleditor/Editor.class"),
            listOf("dev/wildware/udea/leveleditor/"),
        )
        assertEquals(1, violations.size)
    }

    @Test
    fun `scanning no archive at all is a failure, not a pass`() {
        // The whole point of reading the artifact is that a green model check over a leaky jar
        // is the failure mode. A green run over *no* jar is the same failure wearing a hat.
        val message = assertNotNull(ReleaseRules.brokenCheck(":moba", emptyList()))
        assertTrue("no packaged artifact" in message, message)
        assertTrue("UDEA-REL-001" in message, message)
        assertNull(ReleaseRules.brokenCheck(":moba", listOf(jar)))
    }

    @Test
    fun `the classpath rule bans both agent modules on a runtime classpath`() {
        val violations = DependencyRules.violations(
            ":moba",
            "runtimeClasspath",
            ResolvedGraph(
                ":moba",
                listOf(
                    DependencyEdge(":moba", ":udea-agent-host"),
                    DependencyEdge(":udea-agent-host", ":udea-agent"),
                ),
            ),
            listOf(ReleaseRules.CLASSPATH_RULE),
        )
        assertEquals(listOf(":udea-agent", ":udea-agent-host"), violations.map { it.coordinate })
        assertEquals(
            listOf(":moba", ":udea-agent-host", ":udea-agent"),
            violations.first { it.coordinate == ":udea-agent" }.resolutionPath,
        )
    }
}
