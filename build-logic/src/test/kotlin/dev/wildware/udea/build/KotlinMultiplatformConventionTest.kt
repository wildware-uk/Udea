package dev.wildware.udea.build

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `udea.kotlin-multiplatform` and `udea.kotlin-multiplatform-render`, applied to a real build
 * (issue #201, spec sections 3 and 7).
 *
 * The targets a runtime module compiles for are the whole of what these conventions decide, and
 * a target that quietly goes missing is invisible: the build gets faster and greener, and the
 * platform stops being built. So the target set is read back out of the Kotlin Gradle plugin's
 * own model rather than out of the convention's source text.
 */
class KotlinMultiplatformConventionTest {

    /** `name=a,b,c` printed by the fixture, read back as a sorted list. */
    private fun printed(output: String, name: String): List<String> {
        val line = output.lineSequence().firstOrNull { it.startsWith("$name=") }
        assertTrue(line != null, "the fixture printed no `$name=` line:\n$output")
        return line.removePrefix("$name=").split(',').filter { it.isNotEmpty() }.sorted()
    }

    private fun multiplatformFixture(root: File, plugin: String): GradleFixture =
        GradleFixture(root).withVersionCatalog().withCompilerPluginProject().withAndroidSdk().project(
            "sample",
            """
            plugins { id("$plugin") }

            // Read at configuration time, printed at execution: the configuration cache is on
            // in every fixture, and a task that reached back into `kotlin` from `doLast` could
            // not be serialised.
            val targetNames = kotlin.targets.names.filter { it != "metadata" }.sorted()
            tasks.register("printTargets") {
                val names = targetNames
                doLast { println("targets=" + names.joinToString(",")) }
            }
            """.trimIndent(),
        )

    @Test
    fun `the runtime convention targets JVM, Android, Wasm and both iOS ARM targets`(@TempDir root: File) {
        val result = multiplatformFixture(root, "udea.kotlin-multiplatform").build(":sample:printTargets")

        assertEquals(
            listOf("android", "iosArm64", "iosSimulatorArm64", "jvm", "wasmJs"),
            printed(result.output, "targets"),
        )
    }

    @Test
    fun `the render convention is the same set without iOS`(@TempDir root: File) {
        // Spec D2: Kool has no iOS backend, so the one module that draws cannot have an iOS
        // target until it does. Everything else about the two conventions is shared.
        val result = multiplatformFixture(root, "udea.kotlin-multiplatform-render").build(":sample:printTargets")

        assertEquals(listOf("android", "jvm", "wasmJs"), printed(result.output, "targets"))
    }

    @Test
    fun `both conventions carry the stdlib pin and the compiler-plugin gate on check`(@TempDir root: File) {
        // A module moving from `udea.kotlin-library` to multiplatform must not lose the two gates
        // that convention hangs on `check`. `--dry-run` lists what `check` would execute without
        // compiling anything.
        for (plugin in listOf("udea.kotlin-multiplatform", "udea.kotlin-multiplatform-render")) {
            val dir = File(root, plugin).also { it.mkdirs() }
            val output = multiplatformFixture(dir, plugin).build(":sample:check", "--dry-run").output

            assertTrue(":sample:udeaVerifyKotlinPin SKIPPED" in output, "$plugin:\n$output")
            assertTrue(":sample:udeaVerifyCompilerPlugin SKIPPED" in output, "$plugin:\n$output")
            assertTrue(":sample:jvmTest SKIPPED" in output, "$plugin:\n$output")
        }
    }

    /** A multiplatform `:udea-annotations` carrying the module-graph gate, as the real build applies it. */
    private fun gatedAnnotations(fixture: GradleFixture, dependencies: String): GradleFixture =
        fixture.withVersionCatalog().withCompilerPluginProject().withAndroidSdk().project(
            "udea-annotations",
            """
            plugins {
                id("udea.kotlin-multiplatform")
                id("udea.module-graph-check")
                id("udea.legacy-dependency-check")
            }
            ${fixture.repositoryBlock()}
            $dependencies
            """.trimIndent(),
        )

    @Test
    fun `the classpath gates inspect a multiplatform module rather than finding nothing`(@TempDir root: File) {
        // The control for the test below, and the case that failed first: with no classpath
        // named `runtimeClasspath` the gates refused to pass a module they had inspected nothing
        // of, and the stdlib pin listed every target's classpath as unclassified.
        val output = gatedAnnotations(GradleFixture(root), "").build(
            ":udea-annotations:udeaVerifyModuleGraph",
            ":udea-annotations:udeaVerifyNoLegacyDependencies",
            ":udea-annotations:udeaVerifyKotlinPin",
        ).output

        val report = File(root, "udea-annotations/build/reports/udea/module-graph.txt").readText()
        listOf("jvmRuntimeClasspath", "androidRuntimeClasspath", "wasmJsRuntimeClasspath", "iosArm64CompileKlibraries")
            .forEach { assertTrue(report.lines().any { line -> line.startsWith("$it:") }, "$it not scanned:\n$report\n$output") }
    }

    @Test
    fun `a leaf budget broken on the JVM target fails and names that target's classpath`(@TempDir root: File) {
        val fixture = GradleFixture(root).publish("com.squareup:kotlinpoet:2.3.0")
        val result = gatedAnnotations(
            fixture,
            """dependencies { "jvmMainImplementation"("com.squareup:kotlinpoet:2.3.0") }""",
        ).buildAndFail(":udea-annotations:udeaVerifyModuleGraph")

        assertTrue("UDEA-MG-001" in result.output, result.output)
        assertTrue(":udea-annotations jvmRuntimeClasspath -> com.squareup:kotlinpoet" in result.output, result.output)
    }

    @Test
    fun `a JVM module consumes a multiplatform module's test fixtures through testFixtures()`(
        @TempDir root: File,
    ) {
        // `java-test-fixtures` cannot be applied beside the multiplatform plugin, and three
        // JVM modules consume `testFixtures(project(":udea-diagnostics"))`. The consumer here
        // is written exactly as they are, so a variant that stopped matching would fail to
        // resolve rather than silently resolving the main jar.
        val fixture = GradleFixture(root).withVersionCatalog().withCompilerPluginProject().withAndroidSdk()
            .project(
                "library",
                """
                plugins {
                    id("udea.kotlin-multiplatform")
                    id("udea.jvm-test-fixtures")
                }
                """.trimIndent(),
            )
            .project(
                "consumer",
                """
                plugins { id("udea.kotlin-library") }
                dependencies { testImplementation(testFixtures(project(":library"))) }

                val classpath: FileCollection = configurations.getByName("testRuntimeClasspath")
                tasks.register("printClasspath") {
                    inputs.files(classpath)
                    val files = classpath
                    doLast { println("classpath=" + files.files.map { it.name }.sorted().joinToString(",")) }
                }
                """.trimIndent(),
            )
        File(root, "library/src/jvmTestFixtures/kotlin/Fixture.kt").apply { parentFile.mkdirs() }
            .writeText("public object Fixture\n")

        val classpath = printed(fixture.build(":consumer:printClasspath").output, "classpath")

        assertTrue(classpath.any { it.startsWith("library-jvm") && "test-fixtures" in it }, "$classpath")
        assertTrue(classpath.any { it.startsWith("library-jvm") && "test-fixtures" !in it }, "$classpath")
    }
}
