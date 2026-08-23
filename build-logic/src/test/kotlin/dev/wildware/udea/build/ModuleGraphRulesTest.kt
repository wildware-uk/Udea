package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rule set itself, exercised without a Gradle build.
 *
 * The TestKit suites prove the rules are wired to a real resolution; these prove each rule
 * decides what its id says it decides, which is a lot cheaper to check exhaustively here.
 */
class ModuleGraphRulesTest {

    private fun graph(root: String, vararg to: String) =
        ResolvedGraph(root, to.map { DependencyEdge(root, it) })

    private fun violate(project: String, configuration: String, graph: ResolvedGraph) =
        ModuleGraphRules.violations(project, configuration, graph)

    @Test
    fun `UDEA-MG-001 fails anything but the stdlib on the annotations runtime classpath`() {
        val violations = violate(
            ":udea-annotations",
            "runtimeClasspath",
            graph(":udea-annotations", "org.jetbrains.kotlin:kotlin-stdlib", "com.squareup:kotlinpoet"),
        )
        assertEquals(listOf("com.squareup:kotlinpoet"), violations.map { it.coordinate })
        assertEquals(RuleId("UDEA-MG-001"), violations.single().ruleId)
    }

    @Test
    fun `UDEA-MG-001 passes the stdlib and its annotations artifact`() {
        assertTrue(
            violate(
                ":udea-annotations",
                "runtimeClasspath",
                graph(":udea-annotations", "org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations"),
            ).isEmpty(),
        )
    }

    @Test
    fun `UDEA-MG-002 fails a GL backend and the natives it drags in`() {
        val violations = violate(
            ":udea-core",
            "compileClasspath",
            graph(":udea-core", "com.badlogicgames.gdx:gdx-backend-lwjgl3", "org.lwjgl:lwjgl-opengl"),
        )
        assertEquals(
            listOf("com.badlogicgames.gdx:gdx-backend-lwjgl3", "org.lwjgl:lwjgl-opengl"),
            violations.map { it.coordinate },
        )
        assertTrue(violations.all { it.ruleId == RuleId("UDEA-MG-002") })
    }

    @Test
    fun `UDEA-MG-002 allows gdx itself - the ban is on GL and natives, not on maths`() {
        assertTrue(violate(":udea-core", "compileClasspath", graph(":udea-core", "com.badlogicgames.gdx:gdx")).isEmpty())
    }

    @Test
    fun `UDEA-MG-002 fails a native platform artifact`() {
        val violations = violate(
            ":udea-gas",
            "runtimeClasspath",
            graph(":udea-gas", "com.badlogicgames.gdx:gdx-box2d-platform"),
        )
        assertEquals(listOf("com.badlogicgames.gdx:gdx-box2d-platform"), violations.map { it.coordinate })
    }

    @Test
    fun `UDEA-MG-002 leaves udea-render alone - it is the module allowed to see GL`() {
        assertTrue(
            violate(
                ":udea-render",
                "compileClasspath",
                graph(":udea-render", "com.badlogicgames.gdx:gdx-backend-lwjgl3"),
            ).isEmpty(),
        )
    }

    @Test
    fun `UDEA-MG-003 fails gradleApi, which reaches the classpath as a file dependency`() {
        val violations = violate(
            ":udea-assets-compiler",
            "compileClasspath",
            graph(":udea-assets-compiler", "file:Gradle API"),
        )
        assertEquals(RuleId("UDEA-MG-003"), violations.single().ruleId)
    }

    @Test
    fun `UDEA-MG-003 fails a published Gradle module too`() {
        val violations = violate(
            ":udea-assets-compiler",
            "testRuntimeClasspath",
            graph(":udea-assets-compiler", "org.gradle:gradle-core-api"),
        )
        assertEquals(listOf("org.gradle:gradle-core-api"), violations.map { it.coordinate })
    }

    @Test
    fun `UDEA-MG-003 does not govern udea-gradle, which is allowed gradleApi as compileOnly`() {
        assertTrue(violate(":udea-gradle", "compileClasspath", graph(":udea-gradle", "file:Gradle API")).isEmpty())
    }

    @Test
    fun `UDEA-MG-004 fails udea-gradle on a runtime classpath`() {
        val violations = violate(":moba", "runtimeClasspath", graph(":moba", ":udea-gradle"))
        assertEquals(RuleId("UDEA-MG-004"), violations.single().ruleId)
    }

    @Test
    fun `UDEA-MG-004 does not fire on a compile classpath, where a plugin author may need it`() {
        assertTrue(violate(":udea-core", "compileClasspath", graph(":udea-core", ":udea-gradle")).isEmpty())
    }

    @Test
    fun `UDEA-MG-005 fails a scripting host, kotlin-reflect and a classpath scanner in the game`() {
        val violations = violate(
            ":moba",
            "runtimeClasspath",
            graph(
                ":moba",
                "org.jetbrains.kotlin:kotlin-scripting-jvm-host",
                "org.jetbrains.kotlin:kotlin-reflect",
                "org.reflections:reflections",
            ),
        )
        assertEquals(
            listOf(
                "org.jetbrains.kotlin:kotlin-reflect",
                "org.jetbrains.kotlin:kotlin-scripting-jvm-host",
                "org.reflections:reflections",
            ),
            violations.map { it.coordinate },
        )
        assertTrue(violations.all { it.ruleId == RuleId("UDEA-MG-005") })
    }

    @Test
    fun `UDEA-MG-005 governs only the game, not the build-time modules that need scripting`() {
        assertTrue(
            violate(
                ":udea-assets-compiler",
                "runtimeClasspath",
                graph(":udea-assets-compiler", "org.jetbrains.kotlin:kotlin-scripting-jvm-host"),
            ).isEmpty(),
        )
    }

    @Test
    fun `a transitive violation is reported with the path that produced it`() {
        val transitive = ResolvedGraph(
            root = ":udea-core",
            edges = listOf(
                DependencyEdge(":udea-core", "com.example:physics"),
                DependencyEdge("com.example:physics", "org.lwjgl:lwjgl"),
            ),
        )
        val violation = violate(":udea-core", "compileClasspath", transitive).single()
        assertEquals(
            listOf(":udea-core", "com.example:physics", "org.lwjgl:lwjgl"),
            violation.resolutionPath,
        )
        assertTrue("com.example:physics" in violation.describe(), violation.describe())
    }

    @Test
    fun `the headless set is every udea module in settings_gradle_kts except udea-render`() {
        // The gap this closes: `HEADLESS_PROJECTS` used to be a hand-written subset, and a
        // module added to `settings.gradle.kts` joined neither the dependency rule nor the
        // bytecode scan. Deriving the expectation from the settings file makes including a
        // new `udea-*` module a red test rather than a silent hole in UDEA-MG-002.
        val settings = File("../settings.gradle.kts").canonicalFile
        assertTrue(settings.isFile, "settings.gradle.kts not found at ${settings.absolutePath}")
        val included = Regex("""^include\("(udea-[a-z0-9-]+)"\)""", RegexOption.MULTILINE)
            .findAll(settings.readText())
            .map { ":" + it.groupValues[1] }
            .toSortedSet()
        assertTrue(
            included.size > 5,
            "the settings scan found only $included - the regex has stopped matching, so this " +
                "test would pass against nothing",
        )
        assertEquals(
            (included - ":udea-render").toList(),
            ModuleGraphRules.HEADLESS_PROJECTS.sorted(),
            "udea-render is the one module allowed to see GL (spec 4), so every other udea-* " +
                "module must be in ModuleGraphRules.HEADLESS_PROJECTS",
        )
    }

    @Test
    fun `UDEA-MG-002 governs exactly the headless set`() {
        // The dependency rule and the bytecode scan are "the same rule, one level down"
        // (docs/module-graph.md). They are only that while both read HEADLESS_PROJECTS.
        assertEquals(
            ModuleGraphRules.HEADLESS_PROJECTS,
            ModuleGraphRules.NO_GL_OUTSIDE_RENDER.projects,
        )
    }

    @Test
    fun `UDEA-MG-002 covers the modules that were previously in neither gate`() {
        // Each of these was outside both the dependency rule and the bytecode scan, so
        // `implementation(libs.gdx.backend.lwjgl3)` on any of them stayed green twice over.
        listOf(":udea-agent-host", ":udea-diagnostics", ":udea-gradle", ":udea-compiler-plugin").forEach {
            val violations = violate(it, "compileClasspath", graph(it, "com.badlogicgames.gdx:gdx-backend-lwjgl3"))
            assertEquals(RuleId("UDEA-MG-002"), violations.single().ruleId, "$it is not guarded")
        }
    }

    @Test
    fun `every rule id is unique`() {
        val ids = ModuleGraphRules.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate rule id in ModuleGraphRules.ALL: $ids")
    }

    @Test
    fun `every rule id and its rationale is documented in docs_module-graph_md`() {
        // A rule id that appears in a build failure and nowhere else is an error message
        // nobody can act on. This is what stops a rule being added without an explanation.
        val docs = File("../docs/module-graph.md").canonicalFile
        assertTrue(docs.isFile, "docs/module-graph.md not found at ${docs.absolutePath}")
        val text = docs.readText()
        ModuleGraphRules.ALL.forEach { rule ->
            assertTrue(rule.id.value in text, "${rule.id} is not documented in docs/module-graph.md")
        }
        assertTrue(LegacyDependencyRules.ID.value in text, "${LegacyDependencyRules.ID} is not documented")
        assertTrue(ReleaseRules.ARTIFACT_RULE_ID.value in text, "${ReleaseRules.ARTIFACT_RULE_ID} is not documented")
        assertTrue(ReleaseRules.CLASSPATH_RULE.id.value in text, "${ReleaseRules.CLASSPATH_RULE.id} is not documented")
        ReleaseRules.DEFAULT_BANNED_PREFIXES.forEach {
            assertTrue(it in text, "banned release prefix '$it' is not documented in docs/module-graph.md")
        }
    }

    @Test
    fun `the report names the module, the rule id and the offending coordinate`() {
        val report = assertNotNull(
            ModuleGraphRules.report(
                violate(":udea-core", "compileClasspath", graph(":udea-core", "org.lwjgl:lwjgl")),
            ),
        )
        assertTrue(":udea-core" in report, report)
        assertTrue("UDEA-MG-002" in report, report)
        assertTrue("org.lwjgl:lwjgl" in report, report)
    }
}
