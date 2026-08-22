package dev.wildware.udea.compiler.testing

import dev.wildware.udea.diagnostics.UdeaRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The harness testing itself.
 *
 * A compile-testing harness is the one place where "the test passed" can mean "nothing ran",
 * so each property the checker suites lean on is asserted here: spans are repo-relative,
 * `assertCompilesClean` rejects a fixture that failed to compile, and a plugin-free
 * compilation really does produce nothing.
 */
class UdeaCompileTestingTest : UdeaCheckerTest() {

    private val netOnVal = source(
        "Health.kt",
        """
        package udea.fixtures

        import dev.wildware.udea.annotations.Net

        class Health {
            @Net
            val max: Float = 100f
        }
        """,
    )

    @Test
    fun `a diagnostic span is repo-relative and carries no absolute path`() {
        // Spec 5: a span is never absolute. The compiler hands the plugin an absolute path and
        // the harness relativises it against the throwaway directory that stands in for the
        // repository root, which is the same normalisation the Gradle wiring performs.
        val run = compile(netOnVal)

        val span = assertNotNull(run.diagnostics.single().span, run.describe())
        assertEquals("${UdeaCompileTesting.SOURCE_DIR}/Health.kt", span.path)
        assertFalse(
            span.path.startsWith("/") || span.path.getOrNull(1) == ':',
            "the span must not be absolute, was ${span.path}",
        )
        assertFalse(
            run.workDir.absolutePath.replace('\\', '/') in span.path,
            "the span leaked the build machine's directory layout: ${span.path}",
        )
        assertEquals(7, span.startLine)
        assertEquals(9, span.startColumn)
    }

    @Test
    fun `assertCompilesClean fails when the fixture does not compile`() {
        // Otherwise a false-positive regression test would pass because its fixture broke,
        // which is the classic test that cannot fail.
        val failure = assertFailsWith<AssertionError> {
            assertCompilesClean(source("Broken.kt", "package udea.fixtures\n\nclass Broken {"))
        }

        assertTrue("must compile cleanly" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `assertCompilesClean fails when a checker does fire`() {
        val failure = assertFailsWith<AssertionError> { assertCompilesClean(netOnVal) }

        assertTrue(UdeaRules.NET_ON_VAL.id in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `with no plugin applied the same source produces no Udea diagnostic`() {
        // This is the Gradle kill switch's shape: the -Xplugin argument is never produced.
        val run = UdeaCompileTesting.compile(listOf(netOnVal), applyPlugin = false)

        assertEquals(emptyList(), run.diagnostics, run.describe())
        assertEquals(emptyList(), run.otherMessages, run.describe())
    }

    @Test
    fun `the inner kill switch loads the plugin and reports nothing`() {
        val run = compile(netOnVal, pluginOptions = mapOf("enabled" to "false"))

        assertEquals(emptyList(), run.diagnostics, run.describe())
    }

    @Test
    fun `checkers=false silences the checkers without unloading the plugin`() {
        val run = compile(netOnVal, pluginOptions = mapOf("checkers" to "false"))

        assertEquals(emptyList(), run.diagnostics, run.describe())
    }
}
