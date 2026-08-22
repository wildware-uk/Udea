package dev.wildware.udea.diagnostics

/**
 * The `diagnostics.json` serialised form.
 *
 * Two independent producers write this file — the K2 compiler plugin and the build-time
 * asset validator — and spec section 3.6 requires that the Gradle task and the daemon agree.
 * "Agree" is checked by byte comparison, which only works if the encoding is pinned rather
 * than merely conventional, so this encoder fixes every degree of freedom:
 *
 * - **Field order** is fixed by this object, not by a map iteration order.
 * - **Absent values** are written as an explicit `null` rather than omitted, so every
 *   diagnostic object has the same shape and the same key sequence.
 * - **Line endings** are always `\n`, never the platform separator, and the document ends
 *   with exactly one newline.
 * - **Indentation** is two spaces.
 * - **Output is pure ASCII**: anything outside the printable ASCII range is escaped as
 *   `\uXXXX`, so the bytes are identical under UTF-8 and under any ASCII-superset encoding
 *   a producer's default charset might pick.
 *
 * There is deliberately no decoder here: the consumers of this file are an IDE, a CI log and
 * a byte comparison, none of which need one.
 */
public object DiagnosticsJson {
    /**
     * Bumped only when the shape changes incompatibly. Readers must reject a version they do
     * not know rather than guessing.
     */
    public const val FORMAT_VERSION: Int = 1

    /** Encodes [report] in the pinned form described on [DiagnosticsJson]. */
    public fun encode(report: DiagnosticReport): String = buildString {
        append("{\n")
        append("  \"version\": ").append(FORMAT_VERSION).append(",\n")
        append("  \"suppressed\": ").append(report.suppressedCount).append(",\n")
        append("  \"diagnostics\": ")
        if (report.diagnostics.isEmpty()) {
            append("[]\n")
        } else {
            append("[\n")
            report.diagnostics.forEachIndexed { index, diagnostic ->
                appendDiagnostic(diagnostic, INDENT_2)
                append(if (index == report.diagnostics.lastIndex) "\n" else ",\n")
            }
            append("  ]\n")
        }
        append("}\n")
    }

    private fun StringBuilder.appendDiagnostic(diagnostic: UdeaDiagnostic, indent: String) {
        val inner = indent + INDENT_1
        append(indent).append("{\n")
        append(inner).append("\"severity\": ").appendJsonString(diagnostic.severity.wireName).append(",\n")
        append(inner).append("\"ruleId\": ").appendJsonString(diagnostic.ruleId).append(",\n")
        append(inner).append("\"message\": ").appendJsonString(diagnostic.message).append(",\n")
        append(inner).append("\"span\": ").appendSpan(diagnostic.span).append(",\n")
        append(inner).append("\"assetId\": ").appendJsonStringOrNull(diagnostic.assetId).append(",\n")
        append(inner).append("\"causedBy\": ").appendJsonStringOrNull(diagnostic.causedBy).append(",\n")
        append(inner).append("\"fix\": ")
        appendFix(diagnostic.fix, inner)
        append("\n").append(indent).append("}")
    }

    private fun StringBuilder.appendFix(fix: Fix?, indent: String): StringBuilder {
        if (fix == null) return append("null")
        val inner = indent + INDENT_1
        val replacementIndent = inner + INDENT_1
        append("{\n")
        append(inner).append("\"description\": ").appendJsonString(fix.description).append(",\n")
        append(inner).append("\"replacements\": ")
        if (fix.replacements.isEmpty()) {
            append("[]\n")
        } else {
            append("[\n")
            fix.replacements.forEachIndexed { index, replacement ->
                append(replacementIndent).append("{\n")
                append(replacementIndent).append(INDENT_1).append("\"span\": ")
                    .appendSpan(replacement.span).append(",\n")
                append(replacementIndent).append(INDENT_1).append("\"newText\": ")
                    .appendJsonString(replacement.newText).append("\n")
                append(replacementIndent).append("}")
                append(if (index == fix.replacements.lastIndex) "\n" else ",\n")
            }
            append(inner).append("]\n")
        }
        return append(indent).append("}")
    }

    /** Spans are written on one line: they are five short numbers and a path. */
    private fun StringBuilder.appendSpan(span: SourceSpan?): StringBuilder {
        if (span == null) return append("null")
        append("{\"path\": ").appendJsonString(span.path)
        append(", \"startLine\": ").append(span.startLine)
        append(", \"startColumn\": ").append(span.startColumn)
        append(", \"endLine\": ").append(span.endLine)
        append(", \"endColumn\": ").append(span.endColumn)
        return append("}")
    }

    private fun StringBuilder.appendJsonStringOrNull(value: String?): StringBuilder =
        if (value == null) append("null") else appendJsonString(value)

    private fun StringBuilder.appendJsonString(value: String): StringBuilder {
        append('"')
        for (char in value) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char == '\b' -> append("\\b")
                char < ' ' || char > '~' -> append("\\u").append(char.code.toHex4())
                else -> append(char)
            }
        }
        return append('"')
    }

    private fun Int.toHex4(): String = toString(16).padStart(4, '0')

    private const val INDENT_1 = "  "
    private const val INDENT_2 = "    "
}
