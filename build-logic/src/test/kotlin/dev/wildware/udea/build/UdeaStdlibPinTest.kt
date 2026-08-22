package dev.wildware.udea.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stdlib pin's coverage, and the shape of its escape hatch.
 *
 * The pin is only worth having if the list of what it covers is deliberate. These are the
 * tests that stop an exemption being added silently, which is the exact failure the pin
 * replaced: a 2.3.21 stdlib under a 2.2.10 pin that nothing complained about.
 */
class UdeaStdlibPinTest {

    @Test
    fun `an ordinary module has every classpath pinned`() {
        assertEquals(
            UdeaStdlibPin.PINNED_CONFIGURATIONS,
            UdeaStdlibPin.pinnedConfigurationsFor(":udea-core"),
        )
    }

    @Test
    fun `the pin covers compile, runtime, test and test-fixture classpaths`() {
        // Missing one of these is how the drift got in: the previous pin covered compile and
        // runtime only, so udea-codegen's tests resolved 2.3.20 and udea-core resolved 2.3.21
        // on every classpath at once.
        assertTrue("compileClasspath" in UdeaStdlibPin.PINNED_CONFIGURATIONS)
        assertTrue("runtimeClasspath" in UdeaStdlibPin.PINNED_CONFIGURATIONS)
        assertTrue("testCompileClasspath" in UdeaStdlibPin.PINNED_CONFIGURATIONS)
        assertTrue("testRuntimeClasspath" in UdeaStdlibPin.PINNED_CONFIGURATIONS)
        assertTrue("testFixturesCompileClasspath" in UdeaStdlibPin.PINNED_CONFIGURATIONS)
        assertTrue("testFixturesRuntimeClasspath" in UdeaStdlibPin.PINNED_CONFIGURATIONS)
    }

    @Test
    fun `the Kotlin plugin's own tool classpaths are deliberately not pinned`() {
        // Forcing the project's stdlib onto `ksp` or `kotlinCompilerPluginClasspath` would
        // downgrade the stdlib of the compiler that compiles the project - a rule meant to
        // protect the compiler breaking it instead.
        assertFalse("ksp" in UdeaStdlibPin.PINNED_CONFIGURATIONS)
        assertFalse("kotlinCompilerPluginClasspath" in UdeaStdlibPin.PINNED_CONFIGURATIONS)
    }

    @Test
    fun `an exempt configuration is removed from that module only`() {
        val codegen = UdeaStdlibPin.pinnedConfigurationsFor(":udea-codegen")
        assertFalse("testRuntimeClasspath" in codegen)
        assertTrue("compileClasspath" in codegen, "the shipped processor jar must stay pinned")
        assertTrue("testRuntimeClasspath" in UdeaStdlibPin.pinnedConfigurationsFor(":udea-core"))
    }

    @Test
    fun `every exemption states a reason`() {
        UdeaStdlibPin.EXEMPTIONS.forEach {
            assertTrue(
                it.reason.isNotBlank(),
                "${it.projectPath} ${it.configuration} is exempt from the stdlib pin with no reason given",
            )
        }
    }

    @Test
    fun `an exemption names a configuration the pin would otherwise cover`() {
        // An exemption for a configuration nothing pins is dead configuration that reads as a
        // decision, and would quietly stop matching if PINNED_CONFIGURATIONS were renamed.
        UdeaStdlibPin.EXEMPTIONS.forEach {
            assertTrue(
                it.configuration in UdeaStdlibPin.PINNED_CONFIGURATIONS,
                "${it.configuration} is exempt from a pin that never applied to it",
            )
        }
    }
}
