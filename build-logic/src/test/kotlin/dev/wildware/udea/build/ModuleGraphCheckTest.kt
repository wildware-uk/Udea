package dev.wildware.udea.build

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `udeaVerifyModuleGraph`, one deliberately violating build per rule id.
 *
 * Every rule here passes trivially on the tree as it stands today, which is exactly why each
 * one needs a build that breaks it: a rule nobody has seen fail is indistinguishable from a
 * rule that cannot.
 */
class ModuleGraphCheckTest {

    private val gate = "udea.module-graph-check"

    @Test
    fun `UDEA-MG-001 fails an extra dependency on the annotations runtime classpath`(
        @TempDir root: File,
    ) {
        val fixture = GradleFixture(root).publish("com.squareup:kotlinpoet:2.3.0")
        fixture.project(
            "udea-annotations",
            gatedProject(
                gate,
                """
                ${fixture.repositoryBlock()}
                dependencies { implementation("com.squareup:kotlinpoet:2.3.0") }
                """.trimIndent(),
            ),
        )

        val result = fixture.buildAndFail(":udea-annotations:udeaVerifyModuleGraph")

        assertTrue("UDEA-MG-001" in result.output, result.output)
        assertTrue("com.squareup:kotlinpoet" in result.output, result.output)
        assertTrue(":udea-annotations" in result.output, result.output)
    }

    @Test
    fun `UDEA-MG-002 fails a GL backend on the kernel`(@TempDir root: File) {
        val fixture = GradleFixture(root).publish("com.badlogicgames.gdx:gdx-backend-lwjgl3:1.13.5")
        fixture.project(
            "udea-core",
            gatedProject(
                gate,
                """
                ${fixture.repositoryBlock()}
                dependencies { implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:1.13.5") }
                """.trimIndent(),
            ),
        )

        val result = fixture.buildAndFail(":udea-core:udeaVerifyModuleGraph")

        assertTrue("UDEA-MG-002" in result.output, result.output)
        assertTrue("com.badlogicgames.gdx:gdx-backend-lwjgl3" in result.output, result.output)
        assertTrue(":udea-core" in result.output, result.output)
    }

    @Test
    fun `UDEA-MG-002 catches GL arriving transitively and prints the path`(@TempDir root: File) {
        val fixture = GradleFixture(root)
            .publish("org.lwjgl:lwjgl:3.3.3")
            .publish("com.example:physics:1.0", dependencies = listOf("org.lwjgl:lwjgl:3.3.3"))
        fixture.project(
            "udea-core",
            gatedProject(
                gate,
                """
                ${fixture.repositoryBlock()}
                dependencies { implementation("com.example:physics:1.0") }
                """.trimIndent(),
            ),
        )

        val result = fixture.buildAndFail(":udea-core:udeaVerifyModuleGraph")

        assertTrue("UDEA-MG-002" in result.output, result.output)
        assertTrue(
            ":udea-core -> com.example:physics -> org.lwjgl:lwjgl" in result.output,
            "the failure must say how the native arrived:\n${result.output}",
        )
    }

    @Test
    fun `UDEA-MG-002 allows gdx itself on the kernel - the ban is GL, not maths`(
        @TempDir root: File,
    ) {
        val fixture = GradleFixture(root).publish("com.badlogicgames.gdx:gdx:1.13.5")
        fixture.project(
            "udea-core",
            gatedProject(
                gate,
                """
                ${fixture.repositoryBlock()}
                dependencies { implementation("com.badlogicgames.gdx:gdx:1.13.5") }
                """.trimIndent(),
            ),
        )

        val result = fixture.build(":udea-core:udeaVerifyModuleGraph")

        assertFalse("UDEA-MG-002" in result.output, result.output)
    }

    @Test
    fun `UDEA-MG-003 fails gradleApi on the asset compiler`(@TempDir root: File) {
        // gradleApi() is a file dependency and appears in no component graph. If this test
        // ever passes while the dependency is declared, the scan has stopped reading
        // artifacts and UDEA-MG-003 has become decorative.
        val fixture = GradleFixture(root).project(
            "udea-assets-compiler",
            gatedProject(gate, "dependencies { implementation(gradleApi()) }"),
        )

        val result = fixture.buildAndFail(":udea-assets-compiler:udeaVerifyModuleGraph")

        assertTrue("UDEA-MG-003" in result.output, result.output)
        assertTrue("Gradle API" in result.output, result.output)
    }

    @Test
    fun `UDEA-MG-004 fails a runtime dependency on udea-gradle`(@TempDir root: File) {
        val fixture = GradleFixture(root)
            .project("udea-gradle", "plugins { `java-library` }")
            .project(
                "moba",
                gatedProject(gate, "dependencies { implementation(project(\":udea-gradle\")) }"),
            )

        val result = fixture.buildAndFail(":moba:udeaVerifyModuleGraph")

        assertTrue("UDEA-MG-004" in result.output, result.output)
        assertTrue(":udea-gradle" in result.output, result.output)
        assertTrue("runtimeClasspath" in result.output, result.output)
    }

    @Test
    fun `UDEA-MG-005 fails a scripting host on the game's runtime classpath`(@TempDir root: File) {
        val fixture = GradleFixture(root)
            .publish("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.2.10")
            .publish("org.reflections:reflections:0.10.2")
        fixture.project(
            "moba",
            gatedProject(
                gate,
                """
                ${fixture.repositoryBlock()}
                dependencies {
                    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.2.10")
                    implementation("org.reflections:reflections:0.10.2")
                }
                """.trimIndent(),
            ),
        )

        val result = fixture.buildAndFail(":moba:udeaVerifyModuleGraph")

        assertTrue("UDEA-MG-005" in result.output, result.output)
        assertTrue("org.jetbrains.kotlin:kotlin-scripting-jvm-host" in result.output, result.output)
        assertTrue("org.reflections:reflections" in result.output, result.output)
    }

    @Test
    fun `the gate is reachable from check`(@TempDir root: File) {
        val fixture = GradleFixture(root)
            .project("udea-gradle", "plugins { `java-library` }")
            .project(
                "moba",
                gatedProject(gate, "dependencies { implementation(project(\":udea-gradle\")) }"),
            )

        val result = fixture.buildAndFail(":moba:check")

        assertTrue("UDEA-MG-004" in result.output, result.output)
    }
}
