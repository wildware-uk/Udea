package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decisions behind applying the K2 plugin: which modules, with which options, and what
 * `-Pudea.compilerPlugin.enabled=false` actually removes.
 *
 * These are unit tests over [UdeaCompilerPluginWiring] rather than a Gradle run, and that is
 * the division of labour on purpose. What a *build* proves — that the plugin really lands on a
 * real compilation — is proved by `udeaVerifyCompilerPlugin` on every module of every build,
 * and by the `checkers-fire` CI leg, which compiles a deliberately broken component and reads
 * the rule id back. What a build cannot prove is the failure branches: a green tree never
 * executes them.
 */
class UdeaCompilerPluginWiringTest {

    private val repoRoot = File("..").canonicalFile

    private val pluginBuildScript = repoRoot.resolve("udea-compiler-plugin/build.gradle.kts")

    private val pluginContract = repoRoot.resolve(
        "udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/UdeaCompilerPlugin.kt",
    )

    // --- which modules ------------------------------------------------------------------

    @Test
    fun `every module on the convention gets the plugin, whichever build it is in`() {
        assertTrue(UdeaCompilerPluginWiring.appliesTo(":udea-core", enabled = true))
        assertTrue(UdeaCompilerPluginWiring.appliesTo(":udea-render", enabled = true))
        assertTrue(UdeaCompilerPluginWiring.appliesTo(":moba:game", enabled = true))

        // The projects of a game in its own repository (issue #265). They are called whatever
        // that repository calls them and they are on the same convention, so they get the same
        // checkers. This used to ask `ModuleGraphRules.governs`, which answered "does the path
        // start with :udea- or :moba" - false for every path below, so an outside game's
        // checkers were off and nothing said so.
        assertTrue(UdeaCompilerPluginWiring.appliesTo(":game", enabled = true))
        assertTrue(UdeaCompilerPluginWiring.appliesTo(":desktop", enabled = true))
        assertTrue(UdeaCompilerPluginWiring.appliesTo(":robot:units", enabled = true))
    }

    @Test
    fun `the flag removes the plugin from every module, including moba`() {
        // Spec 7's degrade path. If this ever returns true for one module, that module keeps
        // compiling with the checkers a developer believes they switched off, and the
        // `plugin-disabled` CI leg goes back to proving nothing.
        val everyKind = listOf(":udea-core", ":udea-render", ":moba:game", ":udea-annotations", ":game")
        everyKind.forEach {
            assertFalse(
                UdeaCompilerPluginWiring.appliesTo(it, enabled = false),
                "-Pudea.compilerPlugin.enabled=false must remove the plugin from $it",
            )
        }
    }

    @Test
    fun `the plugin is not applied to itself or to anything on its runtime classpath`() {
        UdeaCompilerPluginWiring.EXCLUDED_PROJECTS.forEach {
            assertFalse(
                UdeaCompilerPluginWiring.appliesTo(it, enabled = true),
                "$it is excluded but appliesTo accepted it; Gradle would answer with a " +
                    "circular task dependency rather than a diagnostic",
            )
        }
    }

    @Test
    fun `the exclusions are exactly the plugin project and its own project dependencies`() {
        // The tripwire on the cycle. `udea-compiler-plugin` today declares
        // `implementation(project(":udea-annotations"))` and `":udea-diagnostics"`; both are on
        // the classpath that `-Xplugin` loads, so applying the plugin to either asks Gradle to
        // build a jar in order to build itself. A fourth project dependency added to that
        // build script without widening EXCLUSIONS is a circular-dependency failure with no
        // explanation attached - this test is the explanation, raised at the right moment.
        assertTrue(pluginBuildScript.isFile, "not found: $pluginBuildScript")
        val declared = Regex("""project\("(:[A-Za-z0-9\-]+)"\)""")
            .findAll(pluginBuildScript.readText())
            .map { it.groupValues[1] }
            .toSortedSet()
        val expected = (declared + UdeaCompilerPluginWiring.PLUGIN_PROJECT_PATH).toSortedSet()

        assertTrue(
            declared.isNotEmpty(),
            "no project(...) dependency was found in $pluginBuildScript, so this test read " +
                "nothing and would pass however the exclusions drifted",
        )
        assertEquals(
            expected.toList(),
            UdeaCompilerPluginWiring.EXCLUDED_PROJECTS.toSortedSet().toList(),
            "udea-compiler-plugin's project dependencies and the wiring's exclusion list have " +
                "drifted apart",
        )
    }

    @Test
    fun `every exclusion states a reason`() {
        UdeaCompilerPluginWiring.EXCLUSIONS.forEach {
            assertTrue(
                it.reason.isNotBlank(),
                "${it.projectPath} is excluded from the K2 plugin with no reason given",
            )
        }
    }

    @Test
    fun `skipReason names the flag and the cycle separately`() {
        assertNull(UdeaCompilerPluginWiring.skipReason(":udea-core", enabled = true))
        assertTrue(
            UdeaBuildFlags.COMPILER_PLUGIN_ENABLED in
                UdeaCompilerPluginWiring.skipReason(":udea-core", enabled = false).orEmpty(),
        )
        assertTrue(
            "runtime classpath" in
                UdeaCompilerPluginWiring.skipReason(":udea-diagnostics", enabled = true).orEmpty(),
        )
    }

    // --- the CLI contract, written twice --------------------------------------------------

    @Test
    fun `the plugin id and option names match the ones udea-compiler-plugin mints`() {
        // `build-logic` cannot depend on `udea-compiler-plugin` - it is a subproject of the
        // build this code configures - so the CLI contract is written out twice and this is
        // the only thing comparing the copies. A drift here does not fail a build: the
        // compiler rejects an unknown `-P plugin:` option, but an option that quietly stops
        // being passed just switches a checker off.
        assertTrue(pluginContract.isFile, "not found: $pluginContract")
        val contract = pluginContract.readText()

        assertTrue(
            """PLUGIN_ID: String = "${UdeaCompilerPluginWiring.PLUGIN_ID}"""" in contract,
            "UdeaCompilerPlugin.PLUGIN_ID is no longer '${UdeaCompilerPluginWiring.PLUGIN_ID}', " +
                "so -Xplugin would load a plugin that ignores every -P argument this build sends",
        )
        UdeaCompilerPluginWiring.OPTIONS.forEach { option ->
            assertTrue(
                """= "${option.key}"""" in contract,
                "this build passes 'plugin:${UdeaCompilerPluginWiring.PLUGIN_ID}:${option.key}', " +
                    "which UdeaCompilerPlugin no longer declares",
            )
        }
    }

    @Test
    fun `every option is a strict boolean with a stated reason`() {
        // UdeaCommandLineProcessor calls toBooleanStrictOrNull and throws on anything else, so
        // a value of "1" or "yes" here would fail every compilation in the tree at once.
        UdeaCompilerPluginWiring.OPTIONS.forEach {
            assertTrue(
                it.value == "true" || it.value == "false",
                "option ${it.key}=${it.value} is not a strict boolean",
            )
            assertTrue(it.reason.isNotBlank(), "option ${it.key} is passed with no reason given")
        }
    }

    @Test
    fun `synthesis stays off, because the IDE spike returned NO-GO`() {
        // Issue #43: IntelliJ loads only its eleven bundled K2 registrars, so a synthesised
        // declaration resolves in the build and is red in the editor. The option is pinned
        // here so that a change to the plugin's own default cannot turn it on.
        assertEquals(
            "false",
            UdeaCompilerPluginWiring.OPTIONS.single { it.key == "synthesis" }.value,
        )
        assertEquals(
            "true",
            UdeaCompilerPluginWiring.OPTIONS.single { it.key == "checkers" }.value,
        )
    }

    @Test
    fun `the plugin coordinate cannot resolve from a repository`() {
        // If the substitution stops being registered, this is what decides whether the build
        // fails loudly or silently compiles against a stale jar from mavenLocal. Both are
        // possible; only one is debuggable.
        assertFalse(
            UdeaCompilerPluginWiring.ARTIFACT_VERSION == "1.0-SNAPSHOT",
            "the substituted-away coordinate must not name the version this project publishes, " +
                "or a broken substitution resolves a stale jar out of mavenLocal() in silence",
        )
    }

    // --- the classpath gate's failure branches ---------------------------------------------

    private val appliedClasspaths = listOf("kotlinCompilerPluginClasspathMain")
    private val theProject = "project ${UdeaCompilerPluginWiring.PLUGIN_PROJECT_PATH}"
    private val kgpOwnJars = listOf("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.2.10")

    /**
     * The plugin project as a build that *included* this one renders it.
     *
     * Gradle renders a project component as `project <identity path>`, and an included build's
     * identity path carries that build's name in front of the project path. A game outside this
     * repository resolves the published jar rather than this (issue #265, and the tests at the
     * end of this file), but a build that includes the engine's build still gets the project -
     * and that is the same "the jar this build tree just compiled" the check is about.
     */
    private val theProjectThroughAComposite = "project :Udea:udea-compiler-plugin"

    @Test
    fun `a correctly wired module passes`() {
        assertNull(
            UdeaCompilerPluginWiring.classpathViolation(
                projectPath = ":udea-core",
                enabled = true,
                pluginClasspaths = appliedClasspaths,
                resolvedComponents = kgpOwnJars + theProject,
            ),
        )
    }

    @Test
    fun `a build that includes the engine's build resolves the project and passes`() {
        assertNull(
            UdeaCompilerPluginWiring.classpathViolation(
                projectPath = ":game",
                enabled = true,
                pluginClasspaths = appliedClasspaths,
                resolvedComponents = kgpOwnJars + theProjectThroughAComposite,
                buildCompilesPlugin = false,
            ),
        )
    }

    @Test
    fun `a project component is one thing and a published artifact is another`() {
        assertTrue(UdeaCompilerPluginWiring.isPluginProject(theProject))
        assertTrue(UdeaCompilerPluginWiring.isPluginProject(theProjectThroughAComposite))

        // The case the check exists for: the coordinate resolved from a repository, which is a
        // jar somebody published months ago rather than the one this build tree compiled.
        assertFalse(UdeaCompilerPluginWiring.isPluginProject("dev.wildware.udea:udea-compiler-plugin:1.0-SNAPSHOT"))
        // And a project that merely ends in something similar.
        assertFalse(UdeaCompilerPluginWiring.isPluginProject("project :my-udea-compiler-plugin-fork"))
    }

    @Test
    fun `a module that should have the plugin and does not, fails`() {
        val violation = UdeaCompilerPluginWiring.classpathViolation(
            projectPath = ":udea-core",
            enabled = true,
            pluginClasspaths = appliedClasspaths,
            resolvedComponents = kgpOwnJars,
        )
        assertNotNull(violation)
        assertTrue("silently off" in violation, violation)
    }

    @Test
    fun `a module with no plugin classpath at all fails rather than passing vacuously`() {
        // The check that walks nothing. Without this branch, a module on which the Kotlin
        // plugin never ran would be indistinguishable from one that is correctly wired.
        val violation = UdeaCompilerPluginWiring.classpathViolation(
            projectPath = ":udea-core",
            enabled = true,
            pluginClasspaths = emptyList(),
            resolvedComponents = emptyList(),
        )
        assertNotNull(violation)
        assertTrue("inspected nothing" in violation, violation)
    }

    @Test
    fun `resolving the plugin from a repository instead of the project fails`() {
        val violation = UdeaCompilerPluginWiring.classpathViolation(
            projectPath = ":udea-core",
            enabled = true,
            pluginClasspaths = appliedClasspaths,
            resolvedComponents = listOf("dev.wildware.udea:udea-compiler-plugin:1.0-SNAPSHOT"),
        )
        assertNotNull(violation)
        assertTrue("substitution" in violation, violation)
    }

    @Test
    fun `the plugin on a module that must not have it fails, and says why not`() {
        val violation = UdeaCompilerPluginWiring.classpathViolation(
            projectPath = ":udea-diagnostics",
            enabled = true,
            pluginClasspaths = appliedClasspaths,
            resolvedComponents = listOf(theProject),
        )
        assertNotNull(violation)
        assertTrue("runtime classpath" in violation, violation)
    }

    @Test
    fun `with the flag off, the plugin still being on a classpath fails`() {
        // This is the branch that turns the `plugin-disabled` CI leg from "the flag parses"
        // into "the flag removes the plugin". Before the wiring landed there was nothing on
        // that classpath to remove, and the leg passed regardless.
        val violation = UdeaCompilerPluginWiring.classpathViolation(
            projectPath = ":moba",
            enabled = false,
            pluginClasspaths = appliedClasspaths,
            resolvedComponents = listOf(theProject),
        )
        assertNotNull(violation)
        assertTrue(UdeaBuildFlags.COMPILER_PLUGIN_ENABLED in violation, violation)
    }

    @Test
    fun `with the flag off, an empty plugin classpath is the expected state`() {
        assertNull(
            UdeaCompilerPluginWiring.classpathViolation(
                projectPath = ":moba",
                enabled = false,
                pluginClasspaths = appliedClasspaths,
                resolvedComponents = kgpOwnJars,
            ),
        )
    }

    // --- the build that does not compile the plugin (issue #265) ----------------------------
    //
    // A game in its own repository resolves the engine from Maven, so the compiler plugin arrives
    // as a published jar. That is the one thing the branch above exists to refuse, and refusing it
    // there is right: in *this* build a published jar means the substitution broke and the
    // checkers are whatever somebody published months ago. The two are told apart by whether the
    // build being checked contains `:udea-compiler-plugin` at all.

    private val thePublishedPlugin = "dev.wildware.udea:udea-compiler-plugin:0.1.0-SNAPSHOT"

    @Test
    fun `a game whose build does not compile the plugin may resolve it from a repository`() {
        assertNull(
            UdeaCompilerPluginWiring.classpathViolation(
                projectPath = ":game",
                enabled = true,
                pluginClasspaths = appliedClasspaths,
                resolvedComponents = kgpOwnJars + thePublishedPlugin,
                buildCompilesPlugin = false,
            ),
            "a published jar is the only way an outside game can get the plugin at all",
        )
    }

    @Test
    fun `a build that compiles the plugin still refuses the published jar`() {
        val violation = UdeaCompilerPluginWiring.classpathViolation(
            projectPath = ":udea-core",
            enabled = true,
            pluginClasspaths = appliedClasspaths,
            resolvedComponents = kgpOwnJars + thePublishedPlugin,
            buildCompilesPlugin = true,
        )
        assertNotNull(violation)
        assertTrue("substitution" in violation, violation)
    }

    @Test
    fun `a game with no plugin on the classpath fails like any other module`() {
        // The looser rule above is about *where* the plugin came from, not about whether it is
        // there. Without this, "the build does not compile the plugin" would be a way to have no
        // checkers at all and stay green - which is issue #164 arriving through a new door.
        val violation = UdeaCompilerPluginWiring.classpathViolation(
            projectPath = ":game",
            enabled = true,
            pluginClasspaths = appliedClasspaths,
            resolvedComponents = kgpOwnJars,
            buildCompilesPlugin = false,
        )
        assertNotNull(violation)
        assertTrue("silently off" in violation, violation)
    }

    @Test
    fun `the plugin coordinate names a real version only where it has to resolve`() {
        // In this build the coordinate is substituted onto the project before anything resolves,
        // so it carries a version that exists in no repository - that is what turns a broken
        // substitution into a resolution failure instead of a stale jar. In a game's build there
        // is nothing to substitute onto, so the coordinate has to be one Maven can answer.
        assertEquals(
            UdeaCompilerPluginWiring.ARTIFACT_VERSION,
            UdeaCompilerPluginWiring.artifactVersion(buildCompilesPlugin = true, udeaVersion = "0.1.0-SNAPSHOT"),
            "a build that compiles the plugin must not be able to resolve a published one",
        )
        assertEquals(
            "0.1.0-SNAPSHOT",
            UdeaCompilerPluginWiring.artifactVersion(buildCompilesPlugin = false, udeaVersion = "0.1.0-SNAPSHOT"),
        )
        assertEquals(
            "0.1.0-SNAPSHOT",
            UdeaCompilerPluginWiring.artifactVersion(buildCompilesPlugin = false, udeaVersion = " 0.1.0-SNAPSHOT\n"),
        )
    }

    @Test
    fun `a game that does not say which engine it is on is told so by name`() {
        val thrown = assertFailsWith<IllegalStateException> {
            UdeaCompilerPluginWiring.artifactVersion(buildCompilesPlugin = false, udeaVersion = null)
        }
        assertTrue(UdeaVersion.PROPERTY in (thrown.message ?: ""), thrown.message ?: "")
        // Blank is the same as absent: `-PudeaVersion=` is a property that is set and empty, and
        // a coordinate ending in a colon resolves to nothing with a much worse message.
        assertFailsWith<IllegalStateException> {
            UdeaCompilerPluginWiring.artifactVersion(buildCompilesPlugin = false, udeaVersion = "  ")
        }
    }

    @Test
    fun `the bare declaration bucket is not mistaken for a resolvable classpath`() {
        // Asking `kotlinCompilerPluginClasspath` for resolved artifacts is an error, so the
        // suffix is what separates the classpaths the gate can inspect from the one it cannot.
        assertFalse(
            UdeaCompilerPluginWiring.isCompilationPluginClasspath("kotlinCompilerPluginClasspath"),
        )
        assertTrue(
            UdeaCompilerPluginWiring.isCompilationPluginClasspath("kotlinCompilerPluginClasspathMain"),
        )
        assertFalse(UdeaCompilerPluginWiring.isCompilationPluginClasspath("compileClasspath"))
    }
}
