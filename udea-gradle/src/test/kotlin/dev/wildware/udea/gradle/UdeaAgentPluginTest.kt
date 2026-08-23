package dev.wildware.udea.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The plugin, applied to a bare project by its real id, and driven by a real Gradle.
 *
 * ## Why a whole Gradle invocation per case
 *
 * Everything here is a claim about a *build*: whether a file lands at the root, whether a second
 * run is `UP-TO-DATE`, and - the one that matters - whether `-PdebugPort=7825` becomes
 * `-Dudea.agent.port=7825` on the command line of a **forked JVM**. That last one cannot be
 * asserted from the configuration model at all: `jvmArgumentProviders` are consulted at execution
 * time, so a test that read the provider would be checking the same code twice. The probe below
 * is a real `main` that prints the property it was started with.
 *
 * The decisions that do not need a Gradle - the port placeholder, the port range, the rendered
 * JSON - are tested as decisions in [LaunchDeclarationTest] and [AgentBuildFlagsSourceTest].
 */
class UdeaAgentPluginTest {

    @TempDir
    lateinit var projectDir: File

    /**
     * The plugin under test, as a `buildscript` classpath.
     *
     * Handed over by the build script (see `udea-gradle/build.gradle.kts`) rather than by
     * `withPluginClasspath()`, because that method needs `java-gradle-plugin`, which would put
     * `gradleApi()` on this module's `api` configuration.
     */
    private val pluginClasspath: List<File> by lazy {
        val handOff = assertNotNull(
            System.getProperty(PLUGIN_CLASSPATH_PROPERTY),
            "$PLUGIN_CLASSPATH_PROPERTY was not set; udeaWritePluginClasspath hands the plugin " +
                "under test to this test, and without it every case below would apply nothing",
        )
        File(handOff).readLines().filter { it.isNotBlank() }.map(::File)
    }

    /**
     * The generated script's preamble: `buildscript`, then the plugins, in that order.
     *
     * `buildscript { }` **must** come before `plugins { }`, so the two are not mixed here: the
     * plugins are applied with `apply plugin:` once the classpath is in place. A `plugins { }`
     * block below a `buildscript { }` block is a script Gradle refuses to compile, which is how
     * the first version of these cases failed - ten at once, with a message about block ordering
     * rather than about anything under test.
     */
    private fun preamble(): String {
        val entries = pluginClasspath.joinToString(",\n            ") {
            quoteForGroovy(it.absolutePath)
        }
        return """
            buildscript {
                dependencies {
                    classpath files(
            $entries
                    )
                }
            }
            apply plugin: 'java'
            apply plugin: 'dev.wildware.udea.agent'
        """.trimIndent()
    }

    private fun quoteForGroovy(path: String): String = "'${path.replace("\\", "\\\\")}'"

    private fun write(relative: String, content: String) {
        val file = projectDir.resolve(relative)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    // --- gamebridge.json -----------------------------------------------------------------

    @Test
    fun `a bare project gets a launch declaration with a substitutable port`() {
        write("settings.gradle", "rootProject.name = 'bare-game'")
        write("build.gradle", preamble())

        val result = runner(UdeaAgentPlugin.GENERATE_TASK).build()
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":${UdeaAgentPlugin.GENERATE_TASK}")?.outcome,
        )

        val declaration = projectDir.resolve(LaunchDeclaration.FILE_NAME)
        assertTrue(declaration.isFile, "gamebridge.json must land at the project root")
        val json = declaration.readText()

        assertContains(json, """"name": "bare-game"""")
        assertContains(json, LaunchDeclaration.PORT_PLACEHOLDER)
        assertContains(json, """"cwd": "."""")
        assertContains(json, """"readyTimeoutMs": 180000""")

        // The port range must be clear of the ports people hand out by hand, or the launcher
        // collides with an instance the developer started themselves - the one collision that is
        // silent, because both are the same game.
        val range = Regex(""""portRange": "(\d+)-(\d+)"""").find(json)
        assertNotNull(range, "the declaration must carry a portRange; got $json")
        val low = range.groupValues[1].toInt()
        val high = range.groupValues[2].toInt()
        assertFalse(7777 in low..high, "7777 is hand-assigned")
        assertTrue(low > 7810, "7800-7810 is the hand-assigned block; range started at $low")
    }

    @Test
    fun `two runs are byte-identical and the second is up to date`() {
        write("settings.gradle", "rootProject.name = 'bare-game'")
        write("build.gradle", preamble())

        runner(UdeaAgentPlugin.GENERATE_TASK).build()
        val first = projectDir.resolve(LaunchDeclaration.FILE_NAME).readBytes()

        val second = runner(UdeaAgentPlugin.GENERATE_TASK).build()
        assertEquals(
            TaskOutcome.UP_TO_DATE,
            second.task(":${UdeaAgentPlugin.GENERATE_TASK}")?.outcome,
            "a cacheable task whose output moves between identical runs is never up to date",
        )
        assertContentEquals(first, projectDir.resolve(LaunchDeclaration.FILE_NAME).readBytes())
    }

    @Test
    fun `the extension overrides every field`() {
        write("settings.gradle", "rootProject.name = 'bare-game'")
        write(
            "build.gradle",
            """
            ${preamble()}
            udeaAgent {
                name = 'Orbital Freight'
                portRange = '7900-7909'
                readyTimeoutMs = 240000
                command = 'run-game --port {port}'
                env = ['ORBITAL_DEV': '1']
            }
            """.trimIndent(),
        )

        runner(UdeaAgentPlugin.GENERATE_TASK).build()
        val json = projectDir.resolve(LaunchDeclaration.FILE_NAME).readText()
        assertContains(json, """"name": "Orbital Freight"""")
        assertContains(json, """"command": "run-game --port {port}"""")
        assertContains(json, """"portRange": "7900-7909"""")
        assertContains(json, """"readyTimeoutMs": 240000""")
        assertContains(json, """"ORBITAL_DEV": "1"""")
    }

    // --- the port actually reaching the forked JVM ---------------------------------------

    @Test
    fun `the port property becomes a system property on the forked jvm`() {
        writeProbeProject()

        val result = runner("run", "-P${UdeaAgentPlugin.DEFAULT_PORT_PROPERTY}=7825").build()

        assertContains(result.output, "probe:${UdeaAgentPlugin.AGENT_PORT_PROPERTY}=7825")
        assertContains(result.output, "probe:${UdeaAgentPlugin.RENDER_MODE_PROPERTY}=Offscreen")
    }

    @Test
    fun `the alternate spelling of the port property works too`() {
        writeProbeProject()

        val result = runner("run", "-P${UdeaAgentPlugin.ALTERNATE_PORT_PROPERTY}=7831").build()

        assertContains(result.output, "probe:${UdeaAgentPlugin.AGENT_PORT_PROPERTY}=7831")
    }

    /**
     * A developer's own `run` is left exactly as it was.
     *
     * The interesting half: with no port the provider must contribute *nothing*, not a default.
     * A `-Dudea.render.mode=Offscreen` on a plain `./gradlew run` would hide a developer's window
     * with no explanation.
     */
    @Test
    fun `a run with no port passes no agent arguments at all`() {
        writeProbeProject()

        val result = runner("run").build()

        assertContains(result.output, "probe:${UdeaAgentPlugin.AGENT_PORT_PROPERTY}=null")
        assertContains(result.output, "probe:${UdeaAgentPlugin.RENDER_MODE_PROPERTY}=null")
    }

    @Test
    fun `the render mode is overridable per invocation`() {
        writeProbeProject()

        val result = runner(
            "run",
            "-P${UdeaAgentPlugin.DEFAULT_PORT_PROPERTY}=7826",
            "-P${UdeaAgentPlugin.RENDER_MODE_PROPERTY}=Headless",
        ).build()

        assertContains(result.output, "probe:${UdeaAgentPlugin.RENDER_MODE_PROPERTY}=Headless")
    }

    // --- the debug source set and the generated flag --------------------------------------

    @Test
    fun `the agent source set exists and carries the generated flag`() {
        write("settings.gradle", "rootProject.name = 'bare-game'")
        write("build.gradle", preamble())

        runner(UdeaAgentPlugin.FLAGS_TASK).build()

        val generated = generatedFlagsFile()
        assertTrue(generated.isFile, "expected the flag at $generated")
        assertContains(generated.readText(), "AGENT_ALLOWED: Boolean = true")
    }

    /** The whole reason the flag is generated: `-Pudea.release=true` has to change it. */
    @Test
    fun `a release build generates a flag that refuses to bind`() {
        write("settings.gradle", "rootProject.name = 'bare-game'")
        write("build.gradle", preamble())

        runner(UdeaAgentPlugin.FLAGS_TASK, "-P${UdeaAgentPlugin.RELEASE_PROPERTY}=true").build()

        val generated = generatedFlagsFile()
        assertContains(generated.readText(), "AGENT_ALLOWED: Boolean = false")
    }

    // --- the old plugin's defect, closed ---------------------------------------------------

    /**
     * No Gradle type reaches the game's runtime classpath.
     *
     * The old `gradle-plugin` module declared `implementation(gradleApi())` and `example` depended
     * on it, so the entire Gradle API shipped inside the game. Applying a plugin cannot do that -
     * a `buildscript` classpath and a project's `runtimeClasspath` are different classloaders
     * entirely - and this asserts it rather than reasoning about it, because the failure mode is
     * a 60MB jar nobody looks inside.
     */
    @Test
    fun `applying the plugin puts no gradle jar on the game runtime classpath`() {
        write("settings.gradle", "rootProject.name = 'bare-game'")
        write(
            "build.gradle",
            """
            ${preamble()}
            tasks.register('printRuntimeClasspath') {
                def files = configurations.runtimeClasspath
                doLast { files.each { println "runtime:" + it.name } }
            }
            """.trimIndent(),
        )

        val result = runner("printRuntimeClasspath").build()
        val entries = result.output.lineSequence()
            .filter { it.startsWith("runtime:") }
            .map { it.removePrefix("runtime:") }
            .toList()

        val gradleEntries = entries.filter {
            it.startsWith("gradle-") || it.contains("gradle-api") || it.contains("gradle-installation")
        }
        assertTrue(gradleEntries.isEmpty(), "Gradle jars on the game's runtime classpath: $gradleEntries")
    }

    /**
     * A project generated with a `run` task and a `main` that prints what it was started with.
     *
     * Java rather than Kotlin: a TestKit build applying the Kotlin plugin has to resolve it from a
     * repository, which makes a unit test depend on the network. The property lookup is the same
     * either way, and the property is what is under test.
     */
    private fun writeProbeProject() {
        write("settings.gradle", "rootProject.name = 'probe-game'")
        write(
            "build.gradle",
            """
            ${preamble()}
            tasks.register('run', JavaExec) {
                mainClass = 'Probe'
                classpath = sourceSets.main.runtimeClasspath
            }
            """.trimIndent(),
        )
        write(
            "src/main/java/Probe.java",
            """
            public final class Probe {
                public static void main(String[] args) {
                    System.out.println("probe:${UdeaAgentPlugin.AGENT_PORT_PROPERTY}=" +
                        System.getProperty("${UdeaAgentPlugin.AGENT_PORT_PROPERTY}"));
                    System.out.println("probe:${UdeaAgentPlugin.RENDER_MODE_PROPERTY}=" +
                        System.getProperty("${UdeaAgentPlugin.RENDER_MODE_PROPERTY}"));
                }
            }
            """.trimIndent(),
        )
    }

    /** Where the flag lands for the `bare-game` fixture, derived rather than typed twice. */
    private fun generatedFlagsFile(): File = projectDir.resolve(
        "build/generated/sources/udea-agent-flags/" +
            AgentBuildFlagsSource.defaultPackage("", "bare-game").replace('.', '/') +
            "/" + AgentBuildFlagsSource.FILE_NAME,
    )

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            expected.toList(),
            actual.toList(),
            "two runs of a cacheable task must produce byte-identical output",
        )
    }

    private companion object {
        const val PLUGIN_CLASSPATH_PROPERTY = "udea.gradle.pluginClasspathFile"
    }
}
