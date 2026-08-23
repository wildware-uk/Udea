package dev.wildware.udea.build

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `udeaVerifyKotlinPin`, driven through a real Gradle build.
 *
 * `UdeaKotlinPinTest` proves the *rules* decide correctly; this proves they are attached to
 * a build that can go red. Every other Phase 0 gate has a violating fixture and this one did
 * not, which left the pin in the position it exists to prevent: a check nobody had watched
 * fail.
 *
 * The violation used is the one the pin was structurally unable to see — a resolvable
 * classpath outside [UdeaStdlibPin.PINNED_CONFIGURATIONS]. Before the coverage check, a
 * configuration like this resolved whatever Gradle's highest-wins rule picked and the gate
 * stayed green, which is precisely how `udea-codegen`'s tests came to resolve 2.3.20 under a
 * 2.2.10 pin.
 */
class KotlinPinCheckTest {

    private fun fixture(root: File, extraBuildScript: String): GradleFixture =
        GradleFixture(root).withVersionCatalog().withCompilerPluginProject().project(
            "udea-core",
            """
            plugins { id("udea.kotlin-library") }
            $extraBuildScript
            """.trimIndent(),
        )

    @Test
    fun `an unclassified resolvable configuration fails the gate`(@TempDir root: File) {
        val result = fixture(
            root,
            """
            configurations.create("integrationTestRuntimeClasspath") {
                isCanBeResolved = true
                isCanBeConsumed = false
            }
            """.trimIndent(),
        ).buildAndFail(":udea-core:udeaVerifyKotlinPin")

        assertTrue("integrationTestRuntimeClasspath" in result.output, result.output)
        assertTrue("UdeaStdlibPin.PINNED_CONFIGURATIONS" in result.output, result.output)
    }

    @Test
    fun `a module whose classpaths are all classified passes`(@TempDir root: File) {
        // The control. Without it, the test above would pass just as well if the convention
        // failed for some reason that had nothing to do with the pin.
        val result = fixture(root, "").build(":udea-core:udeaVerifyKotlinPin")

        assertTrue(":udea-core:udeaVerifyKotlinPin" in result.output, result.output)
    }
}
