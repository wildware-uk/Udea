package dev.wildware.udea.build

import java.io.Serializable

/**
 * One rule broken by a document-shaped gate, in the shape `UdeaDiagnostic.toString()` renders.
 *
 * It cannot be a real `UdeaDiagnostic`: `udea-diagnostics` is a project of the *main* build, and
 * `build-logic` is the included build that configures that build, so a dependency would be a
 * cycle - `build-logic` has to compile before `:udea-diagnostics` exists as anything at all. The
 * rule id space is therefore `build-logic`'s own [RuleId], as it is for `UDEA-MG-*` and
 * `UDEA-REL-*`, and only the rendered shape is shared so that a person reading CI output sees one
 * format.
 */
public data class GateFinding(
    public val rule: RuleId,
    /** Repo-relative, forward slashes, never absolute — the `SourceSpan` contract. */
    public val path: String,
    public val line: Int,
    public val message: String,
) : Serializable {
    override fun toString(): String = "$path:$line:1: error: [$rule] $message"
}

/**
 * The failure message for [findings], or null when there are none.
 *
 * @param taskName the gate that found them, so the message says what to re-run.
 * @param remedy what the reader has to change to make the gate pass. Said by the gate rather than
 *   left to a document it names, because a document can be deleted and a message that points at
 *   one that is gone is a dead end.
 */
internal fun gateFailureReport(taskName: String, findings: List<GateFinding>, remedy: String): String? =
    findings.takeIf { it.isNotEmpty() }?.let {
        buildString {
            appendLine("$taskName found ${it.size} problem(s):")
            appendLine()
            it.forEach { finding -> appendLine("  $finding") }
            appendLine()
            append(remedy)
        }
    }
