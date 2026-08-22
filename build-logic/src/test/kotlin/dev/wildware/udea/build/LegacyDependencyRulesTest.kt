package dev.wildware.udea.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The `common` ban. Spec 7's top structural risk, decided here. */
class LegacyDependencyRulesTest {

    @Test
    fun `a direct dependency on common fails`() {
        val violations = LegacyDependencyRules.violations(
            ":udea-net",
            "compileClasspath",
            ResolvedGraph(":udea-net", listOf(DependencyEdge(":udea-net", ":common"))),
        )
        assertEquals(LegacyDependencyRules.ID, violations.single().ruleId)
    }

    @Test
    fun `a dependency two hops away fails, and the message prints the whole path`() {
        // The reason the gate reads the resolved graph rather than declared dependencies:
        // nothing in :udea-gas's build file mentions :common.
        val violations = LegacyDependencyRules.violations(
            ":udea-gas",
            "runtimeClasspath",
            ResolvedGraph(
                root = ":udea-gas",
                edges = listOf(
                    DependencyEdge(":udea-gas", ":udea-core"),
                    DependencyEdge(":udea-core", ":common"),
                ),
            ),
        )
        val violation = violations.single()
        assertEquals(listOf(":udea-gas", ":udea-core", ":common"), violation.resolutionPath)
        assertTrue(":udea-core" in violation.describe(), violation.describe())
    }

    @Test
    fun `every old-tree project is banned, the example subproject included`() {
        val banned = listOf(
            ":common",
            ":gradle-plugin",
            ":level-editor",
            ":idea-plugin",
            ":compose-ui",
            ":example",
            ":example:assets",
        )
        banned.forEach { coordinate ->
            assertTrue(
                LegacyDependencyRules.RULE.isViolatedBy(coordinate, ":udea-core"),
                "$coordinate should be banned",
            )
        }
    }

    @Test
    fun `a rewrite project is not mistaken for an old-tree one`() {
        // `:example` and `:udea-core` share no prefix, but a sloppy `startsWith` rule would
        // ban `:common-something` or miss `:example:assets`. Both directions are checked.
        assertFalse(LegacyDependencyRules.RULE.isViolatedBy(":udea-core", ":moba"))
        assertFalse(LegacyDependencyRules.RULE.isViolatedBy(":commonwealth", ":moba"))
    }

    @Test
    fun `only the rewrite tree is governed`() {
        assertTrue(LegacyDependencyRules.governs(":udea-core"))
        assertTrue(LegacyDependencyRules.governs(":moba"))
        assertFalse(LegacyDependencyRules.governs(":common"))
        assertFalse(LegacyDependencyRules.governs(":example:assets"))
    }

    @Test
    fun `the report names the task, the module, the configuration and the coordinate`() {
        val report = assertNotNull(
            LegacyDependencyRules.report(
                LegacyDependencyRules.violations(
                    ":udea-assets",
                    "testCompileClasspath",
                    ResolvedGraph(":udea-assets", listOf(DependencyEdge(":udea-assets", ":common"))),
                ),
            ),
        )
        assertTrue("udeaVerifyNoLegacyDependencies" in report, report)
        assertTrue(":udea-assets" in report, report)
        assertTrue("testCompileClasspath" in report, report)
        assertTrue(":common" in report, report)
        assertTrue(LegacyDependencyRules.ID.value in report, report)
    }

    @Test
    fun `a clean module produces no report`() {
        val clean = ResolvedGraph(":udea-core", listOf(DependencyEdge(":udea-core", ":udea-annotations")))
        assertNotNull(clean)
        assertTrue(LegacyDependencyRules.violations(":udea-core", "compileClasspath", clean).isEmpty())
    }
}
