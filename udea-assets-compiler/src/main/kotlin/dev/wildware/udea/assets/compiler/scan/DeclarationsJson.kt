package dev.wildware.udea.assets.compiler.scan

import dev.wildware.udea.diagnostics.SourceSpan

/**
 * The `declarations.json` writer: pass 1's output, in a form two checkouts agree on byte for
 * byte.
 *
 * Three properties are load-bearing and each is tested:
 *
 * - **No absolute path, ever.** Every path is a repo-relative [SourceSpan.path], which the
 *   span type itself refuses to construct from a machine path. That is what makes the file
 *   identical when produced from `C:\Users\a\udea` and from `/home/b/udea`, and it is what
 *   keeps a build machine's directory layout out of a shipped artefact.
 * - **Total ordering.** Entries sort by file, then line, then column, then id. A `HashMap`
 *   iteration order reaching this file would make the output differ run to run and destroy
 *   its value as a build input.
 * - **No content hash.** The per-file hashes exist for the scanner's cache and are a
 *   *mechanism*, not a result; putting them here would churn the file on every whitespace
 *   edit and tempt a consumer into treating them as identity.
 *
 * Hand-written rather than reached for a JSON library, for the reason `udea-diagnostics`
 * writes its own: this is a build input consumed by tooling that must not need a serializer
 * on its classpath, and the shape is fifteen lines.
 */
public object DeclarationsJson {

    /** [report] as `declarations.json` text, newline-terminated. */
    public fun write(report: ScanReport): String {
        val declarations = report.declarations.sortedWith(DECLARATION_ORDER)
        val references = report.references.sortedWith(REFERENCE_ORDER)
        return buildString {
            append("{\n")
            append("  \"assetRoot\": ").append(quote(report.assetRoot)).append(",\n")
            append("  \"declarations\": ")
            appendArray(declarations) { declaration ->
                append("{\"id\": ").append(quote(declaration.id))
                append(", \"kind\": ").append(quote(declaration.kind))
                append(", \"name\": ").append(quote(declaration.name))
                appendSpan(declaration.span)
                append("}")
            }
            append(",\n")
            append("  \"references\": ")
            appendArray(references) { reference ->
                append("{\"target\": ").append(quote(reference.target))
                append(", \"from\": ").append(reference.from?.let(::quote) ?: "null")
                appendSpan(reference.span)
                append("}")
            }
            append("\n}\n")
        }
    }

    private fun <T> StringBuilder.appendArray(items: List<T>, render: StringBuilder.(T) -> Unit) {
        if (items.isEmpty()) {
            append("[]")
            return
        }
        append("[\n")
        items.forEachIndexed { index, item ->
            append("    ")
            render(item)
            append(if (index == items.lastIndex) "\n" else ",\n")
        }
        append("  ]")
    }

    private fun StringBuilder.appendSpan(span: SourceSpan) {
        append(", \"file\": ").append(quote(span.path))
        append(", \"startLine\": ").append(span.startLine)
        append(", \"startColumn\": ").append(span.startColumn)
        append(", \"endLine\": ").append(span.endLine)
        append(", \"endColumn\": ").append(span.endColumn)
    }

    /** File, then position, then id: total over any two distinct declarations. */
    private val DECLARATION_ORDER: Comparator<Declaration> =
        compareBy<Declaration> { it.span.path }
            .thenBy { it.span.startLine }
            .thenBy { it.span.startColumn }
            .thenBy { it.id }

    private val REFERENCE_ORDER: Comparator<ReferenceSite> =
        compareBy<ReferenceSite> { it.span.path }
            .thenBy { it.span.startLine }
            .thenBy { it.span.startColumn }
            .thenBy { it.target }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
