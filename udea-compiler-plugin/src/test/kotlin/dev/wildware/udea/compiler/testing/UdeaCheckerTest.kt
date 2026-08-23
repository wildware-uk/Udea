package dev.wildware.udea.compiler.testing

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Base class for every checker test.
 *
 * The three-assertion checklist recorded in `docs/compiler-plugin.md` - a positive case, a
 * negative case and a position assertion - maps onto [assertDiagnostic], [assertCompilesClean]
 * and the `line`/`column` arguments of the first, so a checker that skips one of them is
 * visibly missing a call rather than merely under-tested.
 */
abstract class UdeaCheckerTest {

    /**
     * Compiles [sources] with the plugin loaded and the checkers on.
     *
     * @param extraClasspath roots added to the compilation classpath, for a test that has to
     *   put an asset index where the plugin will actually find it.
     */
    fun compile(
        vararg sources: TestSource,
        pluginOptions: Map<String, String> = emptyMap(),
        extraClasspath: List<File> = emptyList(),
    ): CheckerRun =
        UdeaCompileTesting.compile(sources.toList(), pluginOptions, extraClasspath = extraClasspath)

    /**
     * Compiles [sources] and asserts the checkers found nothing, and that the fixture itself
     * compiled.
     *
     * The second half is not padding: a fixture with a typo produces zero Udea diagnostics for
     * the wrong reason, and a false-positive regression test that passes because its fixture
     * stopped compiling is a test that cannot fail.
     */
    fun assertCompilesClean(vararg sources: TestSource) {
        val run = compile(*sources)
        assertEquals(
            emptyList(),
            run.diagnostics,
            "expected no Udea diagnostics, got:\n" + run.describe(),
        )
        assertEquals(
            emptyList(),
            run.otherMessages,
            "the fixture itself must compile cleanly, or a clean run proves nothing:\n" + run.describe(),
        )
    }

    /**
     * Compiles [source] and asserts its `// expect:` markers match exactly what was reported.
     *
     * @return the run, for a test that also wants to assert message text.
     */
    fun assertMatchesInlineExpectations(source: TestSource): CheckerRun {
        val run = compile(source)
        val expected = InlineExpectations.parse(source)
        assertTrue(
            expected.isNotEmpty(),
            "${source.name} carries no `// expect:` marker, so this assertion cannot fail",
        )
        InlineExpectations.mismatch(source.name, expected, run.diagnostics)?.let { failure ->
            fail(failure + "\ncompiler said:\n" + run.describe())
        }
        return run
    }

    /**
     * Asserts exactly one diagnostic with [ruleId], at [line] and [column], whose message
     * contains [messageContains].
     *
     * Position is part of the assertion, never optional: a rule that fires on the right symbol
     * and a rule that fires on the enclosing file are the same test without it, and only one of
     * them satisfies Phase 0's "red at the property name" demo criterion.
     */
    fun CheckerRun.assertDiagnostic(
        ruleId: String,
        severity: Severity,
        line: Int,
        column: Int,
        messageContains: String,
    ): UdeaDiagnostic {
        val matching = diagnostics.filter { it.ruleId == ruleId }
        assertEquals(
            1,
            matching.size,
            "expected exactly one $ruleId, got ${matching.size}:\n" + describe(),
        )
        val diagnostic = matching.single()
        val span = diagnostic.span ?: fail("$ruleId has no source span:\n" + describe())
        assertEquals(
            "$line:$column",
            "${span.startLine}:${span.startColumn}",
            "$ruleId reported at the wrong position (${span.path}):\n" + describe(),
        )
        assertEquals(severity, diagnostic.severity, "$ruleId has the wrong severity")
        assertTrue(
            messageContains in diagnostic.message,
            "$ruleId message does not contain \"$messageContains\":\n  " + diagnostic.message,
        )
        return diagnostic
    }
}
