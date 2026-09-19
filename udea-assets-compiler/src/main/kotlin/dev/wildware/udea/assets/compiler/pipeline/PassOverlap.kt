package dev.wildware.udea.assets.compiler.pipeline

import dev.wildware.udea.diagnostics.UdeaDiagnostic

/**
 * Pass 2's diagnostics, less any that pass 1 already reported as the same defect.
 *
 * One rule can have two producers. The loop ban (issue #192) is the case today: pass 1 refuses a
 * bare `repeat` syntactically, and pass 2's K2 checker refuses it again by its resolved symbol.
 * `DiagnosticSink` dedupes on rule and whole span, and the two spans differ: pass 1's covers the
 * element, while a compiler report arrives with a start position only. So "the same defect" here
 * means the same rule at the same file, line and column, and pass 1's copy is the one kept,
 * because it carries the full span.
 */
internal fun List<UdeaDiagnostic>.notAlreadyIn(pass1: List<UdeaDiagnostic>): List<UdeaDiagnostic> {
    val reported = pass1.mapNotNullTo(HashSet()) { it.startKey() }
    return filter { it.startKey() !in reported }
}

private fun UdeaDiagnostic.startKey(): Any? {
    val span = span ?: return null
    return listOf(ruleId, span.path, span.startLine, span.startColumn)
}
