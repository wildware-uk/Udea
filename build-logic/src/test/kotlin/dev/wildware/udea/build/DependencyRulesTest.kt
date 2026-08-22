package dev.wildware.udea.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule engine both Phase 0 dependency gates run on. Every decision either gate makes is
 * made here, so these are the tests that can fail; the Gradle tasks contribute only the
 * graph and the exception.
 */
class DependencyRulesTest {

    private val glBan = DependencyRule(
        id = RuleId("UDEA-TEST-001"),
        summary = "no GL",
        rationale = "the kernel is headless",
        specSection = "4",
        projects = setOf(":udea-core"),
        configurations = setOf("compileClasspath"),
        banned = listOf(
            CoordinatePattern("org.lwjgl:*"),
            CoordinatePattern("com.badlogicgames.gdx:*-platform"),
        ),
    )

    @Test
    fun `a wildcard matches a whole group but not a neighbouring one`() {
        assertTrue(CoordinatePattern("org.lwjgl:*").matches("org.lwjgl:lwjgl-opengl"))
        assertFalse(CoordinatePattern("org.lwjgl:*").matches("org.lwjgl3:lwjgl"))
    }

    @Test
    fun `a wildcard in the middle of a name matches only that shape`() {
        val natives = CoordinatePattern("com.badlogicgames.gdx:*-platform")
        assertTrue(natives.matches("com.badlogicgames.gdx:gdx-platform"))
        assertTrue(natives.matches("com.badlogicgames.gdx:gdx-box2d-platform"))
        assertFalse(natives.matches("com.badlogicgames.gdx:gdx"))
    }

    @Test
    fun `a dot in a pattern is literal, not a regex wildcard`() {
        // `org.gradle` must not match `orgXgradle`; the patterns are globs, not regexes, and
        // a rule that over-matched would fail an innocent module.
        assertFalse(CoordinatePattern("org.gradle:*").matches("orgXgradle:core"))
    }

    @Test
    fun `a rule does not apply to a project it does not name`() {
        assertTrue(glBan.appliesTo(":udea-core", "compileClasspath"))
        assertFalse(glBan.appliesTo(":udea-render", "compileClasspath"))
        assertFalse(glBan.appliesTo(":udea-core", "runtimeClasspath"))
    }

    @Test
    fun `an empty project or configuration set means every one scanned`() {
        val everywhere = glBan.copy(projects = emptySet(), configurations = emptySet())
        assertTrue(everywhere.appliesTo(":anything", "testRuntimeClasspath"))
    }

    @Test
    fun `the module under test is never its own violation`() {
        // `allowOnly` lists what a module may drag in, and the module itself is always in its
        // own resolution result. Without this the leaf rule would fail on every project.
        val leaf = DependencyRule(
            id = RuleId("UDEA-TEST-002"),
            summary = "leaf",
            rationale = "leaf",
            specSection = "4",
            allowOnly = listOf(CoordinatePattern("org.jetbrains.kotlin:kotlin-stdlib")),
        )
        assertFalse(leaf.isViolatedBy(":udea-annotations", rootProjectPath = ":udea-annotations"))
        assertTrue(leaf.isViolatedBy("com.squareup:kotlinpoet", rootProjectPath = ":udea-annotations"))
    }

    @Test
    fun `an explicit allowance beats a ban`() {
        val withException = glBan.copy(
            banned = listOf(CoordinatePattern("com.badlogicgames.gdx:*")),
            allowed = listOf(CoordinatePattern("com.badlogicgames.gdx:gdx")),
        )
        assertFalse(withException.isViolatedBy("com.badlogicgames.gdx:gdx", ":udea-core"))
        assertTrue(withException.isViolatedBy("com.badlogicgames.gdx:gdx-backend-lwjgl3", ":udea-core"))
    }

    @Test
    fun `a rule with neither a ban nor an allow list is rejected at construction`() {
        // A rule that cannot fail is worse than no rule: it reports green forever while
        // reading as enforcement.
        val failure = assertFailsWith<IllegalArgumentException> {
            DependencyRule(
                id = RuleId("UDEA-TEST-003"),
                summary = "nothing",
                rationale = "nothing",
                specSection = "4",
            )
        }
        assertTrue("cannot fail" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a rule using both a ban and an allow list is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            DependencyRule(
                id = RuleId("UDEA-TEST-004"),
                summary = "both",
                rationale = "both",
                specSection = "4",
                banned = listOf(CoordinatePattern("a:b")),
                allowOnly = listOf(CoordinatePattern("c:d")),
            )
        }
    }

    @Test
    fun `a blank rule id is rejected`() {
        assertFailsWith<IllegalArgumentException> { RuleId(" ") }
    }

    @Test
    fun `a violation carries the rule id, coordinate and the path that produced it`() {
        val graph = ResolvedGraph(
            root = ":udea-core",
            edges = listOf(
                DependencyEdge(":udea-core", "com.example:widgets"),
                DependencyEdge("com.example:widgets", "org.lwjgl:lwjgl-opengl"),
            ),
        )
        val violations = DependencyRules.violations(":udea-core", "compileClasspath", graph, listOf(glBan))
        assertEquals(1, violations.size)
        val only = violations.single()
        assertEquals(RuleId("UDEA-TEST-001"), only.ruleId)
        assertEquals("org.lwjgl:lwjgl-opengl", only.coordinate)
        assertEquals(
            listOf(":udea-core", "com.example:widgets", "org.lwjgl:lwjgl-opengl"),
            only.resolutionPath,
        )
        assertTrue("com.example:widgets" in only.describe(), only.describe())
    }

    @Test
    fun `violations are ordered by rule then coordinate so the message is diffable`() {
        val graph = ResolvedGraph(
            root = ":udea-core",
            edges = listOf(
                DependencyEdge(":udea-core", "org.lwjgl:lwjgl-stb"),
                DependencyEdge(":udea-core", "org.lwjgl:lwjgl-glfw"),
            ),
        )
        val coordinates = DependencyRules.violations(":udea-core", "compileClasspath", graph, listOf(glBan))
            .map { it.coordinate }
        assertEquals(listOf("org.lwjgl:lwjgl-glfw", "org.lwjgl:lwjgl-stb"), coordinates)
    }

    @Test
    fun `a clean graph produces no report at all`() {
        val clean = ResolvedGraph(":udea-core", listOf(DependencyEdge(":udea-core", "com.badlogicgames.gdx:gdx")))
        val violations = DependencyRules.violations(":udea-core", "compileClasspath", clean, listOf(glBan))
        assertTrue(violations.isEmpty())
        assertNull(DependencyRules.report("udeaVerifyModuleGraph", violations))
    }

    @Test
    fun `the report names the gate, the count and each offender`() {
        val graph = ResolvedGraph(":udea-core", listOf(DependencyEdge(":udea-core", "org.lwjgl:lwjgl")))
        val report = assertNotNull(
            DependencyRules.report(
                "udeaVerifyModuleGraph",
                DependencyRules.violations(":udea-core", "compileClasspath", graph, listOf(glBan)),
            ),
        )
        assertTrue("udeaVerifyModuleGraph" in report, report)
        assertTrue("1 violation" in report, report)
        assertTrue("UDEA-TEST-001" in report, report)
        assertTrue("compileClasspath" in report, report)
        assertTrue("org.lwjgl:lwjgl" in report, report)
    }
}
