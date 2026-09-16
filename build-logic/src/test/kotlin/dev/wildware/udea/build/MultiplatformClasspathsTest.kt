package dev.wildware.udea.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the classpath gates read a multiplatform module (issue #201).
 *
 * `udeaVerifyModuleGraph`, `udeaVerifyNoLegacyDependencies` and `udeaVerifyKotlinPin` name the
 * classpaths they govern the way a JVM module spells them - `runtimeClasspath`,
 * `testCompileClasspath`. A multiplatform module has none of those names: it has one per
 * target, and the gates found nothing to inspect on either converted module until they learned
 * which JVM classpath each one stands for.
 */
class MultiplatformClasspathsTest {

    @Test
    fun `each target's main, test and fixture classpaths stand for their JVM counterparts`() {
        val expected = mapOf(
            "jvmCompileClasspath" to "compileClasspath",
            "jvmRuntimeClasspath" to "runtimeClasspath",
            "jvmMainCompileClasspath" to "compileClasspath",
            "jvmMainRuntimeClasspath" to "runtimeClasspath",
            "jvmTestCompileClasspath" to "testCompileClasspath",
            "jvmTestRuntimeClasspath" to "testRuntimeClasspath",
            "jvmTestFixturesCompileClasspath" to "testFixturesCompileClasspath",
            "jvmTestFixturesRuntimeClasspath" to "testFixturesRuntimeClasspath",
            "androidCompileClasspath" to "compileClasspath",
            "androidRuntimeClasspath" to "runtimeClasspath",
            "androidHostTestCompileClasspath" to "testCompileClasspath",
            "androidHostTestRuntimeClasspath" to "testRuntimeClasspath",
            "wasmJsCompileClasspath" to "compileClasspath",
            "wasmJsRuntimeClasspath" to "runtimeClasspath",
            "wasmJsTestCompileClasspath" to "testCompileClasspath",
            "wasmJsTestRuntimeClasspath" to "testRuntimeClasspath",
            "iosArm64CompileKlibraries" to "compileClasspath",
            "iosArm64TestCompileKlibraries" to "testCompileClasspath",
            "iosSimulatorArm64CompileKlibraries" to "compileClasspath",
            "iosSimulatorArm64TestCompileKlibraries" to "testCompileClasspath",
            "metadataCompileClasspath" to "compileClasspath",
        )

        assertEquals(expected, expected.keys.associateWith { UdeaMultiplatform.jvmRole(it) })
    }

    @Test
    fun `a JVM module's own names and the tool classpaths stand for nothing`() {
        // The control. `agentCompileClasspath` is `moba`'s debug source set: if a lower-camel
        // prefix were enough to be read as a target, it would start being scanned as a
        // `compileClasspath` and a JVM module's gates would change under it.
        listOf(
            "compileClasspath",
            "testRuntimeClasspath",
            "agentCompileClasspath",
            "kotlinCompilerPluginClasspathJvmMain",
            "wasmJsNpmAggregated",
            "iosArm64CInterop",
        ).forEach { assertNull(UdeaMultiplatform.jvmRole(it), it) }
    }

    @Test
    fun `every resolvable classpath of a converted module is classified by the stdlib pin`() {
        // The list `udeaVerifyKotlinPin` printed for `:udea-diagnostics` the first time it ran on
        // the multiplatform module, before any of them was classified. The module is the one
        // with the most: the other converted module has the same set less the two fixture ones.
        val firstReported = listOf(
            "androidCompileClasspath", "androidHostTestCompileClasspath",
            "androidHostTestRuntimeClasspath", "androidJdkImage", "androidRuntimeClasspath",
            "androidTestUtil", "coreLibraryDesugaring", "iosArm64CInterop",
            "iosArm64CompileKlibraries", "iosArm64TestCInterop", "iosArm64TestCompileKlibraries",
            "iosSimulatorArm64CInterop", "iosSimulatorArm64CompileKlibraries",
            "iosSimulatorArm64TestCInterop", "iosSimulatorArm64TestCompileKlibraries",
            "jvmCompileClasspath", "jvmMainCompileClasspath", "jvmMainRuntimeClasspath",
            "jvmRuntimeClasspath", "jvmTestCompileClasspath", "jvmTestFixturesCompileClasspath",
            "jvmTestFixturesRuntimeClasspath", "jvmTestRuntimeClasspath", "lintChecks",
            "lintPublish", "metadataCompileClasspath", "resolvableIosArm64CompilationApi",
            "resolvableIosArm64TestCompilationApi", "resolvableIosSimulatorArm64CompilationApi",
            "resolvableIosSimulatorArm64TestCompilationApi", "swiftPMDependenciesMetadataClasspath",
            "wasmJsCompileClasspath", "wasmJsNpmAggregated", "wasmJsRuntimeClasspath",
            "wasmJsTestCompileClasspath", "wasmJsTestNpmAggregated", "wasmJsTestRuntimeClasspath",
        )

        assertEquals(emptyList(), UdeaStdlibPin.unclassified(firstReported))
    }

    @Test
    fun `a target's compile and runtime classpaths are pinned, not excused as tools`() {
        // Classifying everything as a tool would also empty the list above. These are the
        // classpaths the module's code is compiled and run against on each platform, so the
        // stdlib on them is exactly what the pin is for.
        listOf("jvmRuntimeClasspath", "androidCompileClasspath", "wasmJsTestRuntimeClasspath", "iosArm64CompileKlibraries")
            .forEach { assertTrue(UdeaStdlibPin.isPinned(":udea-diagnostics", it), it) }
        listOf("wasmJsNpmAggregated", "lintChecks", "androidJdkImage")
            .forEach { assertFalse(UdeaStdlibPin.isPinned(":udea-diagnostics", it), it) }
    }
}
