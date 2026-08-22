package dev.wildware.udea.compiler.testing

import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * One `// expect:` marker written in a fixture source.
 *
 * @param ruleId the [UdeaRules] id the fixture expects.
 * @param line 1-based line the diagnostic must land on.
 * @param column 1-based column the diagnostic must land on.
 */
data class InlineExpectation(val ruleId: String, val line: Int, val column: Int) {
    override fun toString(): String = "$ruleId @ $line:$column"
}

/**
 * Lets a fixture source declare, in the fixture itself, exactly which diagnostics it should
 * produce and where.
 *
 * Issue #37 requires that a checker's **position** is asserted and not merely its presence, and
 * that a fixture "drifts loudly". A marker sits next to the code it is about, so a fixture that
 * gains a line and shifts its own defect fails immediately with the two positions side by side,
 * rather than passing because some diagnostic with the right id turned up somewhere.
 *
 * The format is one comment per expected diagnostic, anywhere in the file:
 *
 * ```
 * // expect: UDEA0001 @ 7:9
 * ```
 */
object InlineExpectations {

    private val MARKER = Regex("""//\s*expect:\s*(UDEA\d{4})\s*@\s*(\d+):(\d+)""")

    /**
     * Every marker in [source], in the order written.
     *
     * A marker naming an id that `udea-diagnostics` does not register fails here rather than
     * silently never matching: an unregistered id in a fixture is the same defect the parity
     * test exists to catch, one file earlier.
     */
    fun parse(source: TestSource): List<InlineExpectation> =
        MARKER.findAll(source.text).map { match ->
            val (ruleId, line, column) = match.destructured
            requireNotNull(UdeaRules.byId(ruleId)) {
                "${source.name} expects rule id '$ruleId', which UdeaRules does not register"
            }
            InlineExpectation(ruleId, line.toInt(), column.toInt())
        }.toList()

    /**
     * The assertion failure message for [expected] against [actual], or `null` when they match.
     *
     * Kept as a pure function so [InlineExpectationsTest] can drive both a match and every kind
     * of mismatch without compiling anything, and so the mismatch message itself - which is the
     * whole value of the mechanism - is under test rather than merely under review.
     */
    fun mismatch(
        fileName: String,
        expected: List<InlineExpectation>,
        actual: List<UdeaDiagnostic>,
    ): String? {
        val observed = actual.mapNotNull { diagnostic ->
            val span = diagnostic.span ?: return@mapNotNull null
            InlineExpectation(diagnostic.ruleId, span.startLine, span.startColumn)
        }
        if (expected.sortedWith(ORDER) == observed.sortedWith(ORDER)) return null

        val missing = observed.fold(expected.toMutableList()) { rest, seen -> rest.apply { remove(seen) } }
        val unexpected = expected.fold(observed.toMutableList()) { rest, want -> rest.apply { remove(want) } }
        return buildString {
            append(fileName).append(": inline expectations did not match.\n")
            append("  expected: ").append(expected.joinToString().ifEmpty { "(none)" }).append('\n')
            append("  actual:   ").append(observed.joinToString().ifEmpty { "(none)" }).append('\n')
            for (want in missing) {
                val samePlace = observed.filter { it.line == want.line && it.column == want.column }
                val sameRule = observed.filter { it.ruleId == want.ruleId }
                append("  expected ").append(want)
                when {
                    sameRule.isNotEmpty() -> append(", but ").append(want.ruleId)
                        .append(" was reported at ")
                        .append(sameRule.joinToString { "${it.line}:${it.column}" })
                    samePlace.isNotEmpty() -> append(", but ")
                        .append(samePlace.joinToString { it.ruleId })
                        .append(" was reported there instead")

                    else -> append(", but nothing was reported for it")
                }
                append('\n')
            }
            for (extra in unexpected) {
                append("  unexpected ").append(extra).append('\n')
            }
        }
    }

    private val ORDER: Comparator<InlineExpectation> =
        compareBy({ it.ruleId }, { it.line }, { it.column })
}
