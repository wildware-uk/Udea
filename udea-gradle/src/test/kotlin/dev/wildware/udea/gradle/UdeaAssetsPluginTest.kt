package dev.wildware.udea.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The asset pipeline's *wiring*, applied to a bare project by its real id.
 *
 * ## What is here and what is deliberately not
 *
 * Not here: whether the pipeline compiles a script correctly. Every pass has its own tests in
 * `udea-assets-compiler`, running against real corpora, and re-driving them through a generated
 * TestKit build would be slower and would prove the same thing twice.
 *
 * Here: the four claims that are *only* true of the wiring, and that no unit test can reach.
 *
 * - the tasks exist under their contract names, so a CI job and a brief can name them;
 * - `check` depends on validation, so an invalid asset tree fails a build nobody remembered to
 *   point at the assets;
 * - the generated accessors are on the **main** source set;
 * - and they are **not** on the script compile classpath. That is spec 3.6's rule and the whole
 *   reason the accessors are a separate configuration: `.udea.kts` uses `reference("id")` so an
 *   asset rename does not invalidate every script's compile classpath. It is asserted here
 *   because there is nowhere else it *can* be - the rule is a statement about two Gradle
 *   configurations, and reading the plugin's source to check it is not a test.
 */
class UdeaAssetsPluginTest {

    @TempDir
    lateinit var projectDir: File

    /** @see UdeaAgentPluginTest.pluginClasspath */
    private val pluginClasspath: List<File> by lazy {
        val handOff = assertNotNull(
            System.getProperty("udea.gradle.pluginClasspathFile"),
            "udea.gradle.pluginClasspathFile was not set; udeaWritePluginClasspath hands the " +
                "plugin under test to this test, and without it this case would apply nothing",
        )
        File(handOff).readLines().filter { it.isNotBlank() }.map(::File)
    }

    private fun write(relative: String, content: String) {
        val file = projectDir.resolve(relative)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    /**
     * A build that applies the plugin and prints what the wiring did, without running a pass.
     *
     * The probe prints rather than asserts so a failure shows the actual value in the build log;
     * a `assert` inside a generated script reports "assertion failed" and nothing else.
     */
    private fun writeBuild(extra: String = "") {
        val entries = pluginClasspath.joinToString(",\n            ") {
            "'" + it.absolutePath.replace("\\", "\\\\") + "'"
        }
        write("settings.gradle", "rootProject.name = 'probe'\n")
        write("assets/config.udea.kts", "// no declarations; nothing here runs a pass\n")
        write(
            "build.gradle",
            """
            buildscript {
                dependencies {
                    classpath files(
            $entries
                    )
                }
            }
            apply plugin: 'java'
            apply plugin: 'dev.wildware.udea.assets'

            udea {
                assetRoots.from('assets')
            }

            tasks.register('udeaProbe') {
                doLast {
                    def generated = project.layout.buildDirectory.dir('udea/generated/kotlin').get().asFile
                    println 'PROBE tasks=' + tasks.names.findAll { it.startsWith('udea') }.sort().join(',')
                    println 'PROBE checkDependsOn=' + tasks.named('check').get()
                        .taskDependencies.getDependencies(tasks.named('check').get())
                        .collect { it.name }.sort().join(',')
                    println 'PROBE mainSrcDirsHasGenerated=' +
                        sourceSets.main.java.srcDirs.contains(generated)
                    println 'PROBE scriptClasspathHasGenerated=' +
                        configurations.udeaAssetScript.files.contains(generated)
                    println 'PROBE scriptClasspathIsResolvable=' +
                        configurations.udeaAssetScript.canBeResolved
                }
            }
            $extra
            """.trimIndent() + "\n",
        )
    }

    private fun probe(): String {
        writeBuild()
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("udeaProbe", "--stacktrace")
            .forwardOutput()
            .build()
            .output
    }

    /**
     * Every task the pipeline promises exists, under the name the briefs use.
     *
     * Names in a `const` in the plugin and names in a CI script are two spellings of one thing,
     * and this is the only place they meet.
     */
    @Test
    fun `the pipeline registers its five tasks`() {
        val output = probe()
        val line = output.lines().first { it.startsWith("PROBE tasks=") }
        for (task in listOf(
            UdeaAssetsPlugin.SCAN_TASK,
            UdeaAssetsPlugin.ACCESSORS_TASK,
            UdeaAssetsPlugin.VALIDATE_TASK,
            UdeaAssetsPlugin.PACK_TASK,
            UdeaAssetsPlugin.RELOCATABLE_TASK,
        )) {
            assertContains(line, task, message = "$task is not registered: $line")
        }
    }

    /**
     * `check` validates the assets and checks the outputs are relocatable.
     *
     * Without this a project could have an asset tree that does not compile and a green
     * `./gradlew build`, discovered by whoever next ran the game - which is the loop spec 3.6
     * exists to delete.
     */
    @Test
    fun `check depends on validation and on the relocatability gate`() {
        val line = probe().lines().first { it.startsWith("PROBE checkDependsOn=") }
        assertContains(line, UdeaAssetsPlugin.VALIDATE_TASK, message = line)
        assertContains(line, UdeaAssetsPlugin.RELOCATABLE_TASK, message = line)
    }

    /**
     * The generated accessors are compiled into the game and are **not** on the script classpath.
     *
     * The second half is the load-bearing one and it is easy to break by accident: adding the
     * generated directory to `udeaAssetScript` would make `GameAssets` resolve inside a
     * `.udea.kts`, which looks like a convenience and is a rename-invalidates-every-script cycle
     * (spec 3.6). Add `configurations.udeaAssetScript.dependencies.add(...)` for that directory in
     * `UdeaAssetsPlugin.wireSourceSets` and this fails; nothing else in the repository notices.
     */
    @Test
    fun `the accessors are on main and never on the script classpath`() {
        val output = probe()
        assertTrue(
            "PROBE mainSrcDirsHasGenerated=true" in output,
            "the generated accessors are not compiled into the game: $output",
        )
        assertTrue(
            "PROBE scriptClasspathHasGenerated=false" in output,
            "the generated accessors reached the .udea.kts compile classpath, which spec 3.6 " +
                "forbids: an asset rename would then recompile every script in the tree",
        )
        assertTrue("PROBE scriptClasspathIsResolvable=true" in output, output)
    }
}
