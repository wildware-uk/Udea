package dev.wildware.udea.compiler.kdoc

import dev.wildware.udea.diagnostics.SourceSpan

/**
 * One documented declaration, as it appears in `kdoc-index.json`.
 *
 * @param fqn the declaration's fully qualified name - `dev.wildware.udea.assets.Ability`, or
 *   `dev.wildware.udea.assets.Ability.cooldown` for a member. This plus a [KDocParam.name] is
 *   the identity issue #42 specifies, and it is what `udea-codegen` looks a generated member
 *   up by.
 * @param span where the declaration is, repo-relative (spec 5: never absolute).
 * @param doc the harvested documentation.
 */
internal data class KDocEntry(
    val fqn: String,
    val span: SourceSpan,
    val doc: KDocBlock,
)

/**
 * The `kdoc-index.json` serialised form.
 *
 * Issue #42 requires two clean builds to produce byte-identical output, so this fixes every
 * degree of freedom the way `DiagnosticsJson` does for `diagnostics.json`: entries are sorted
 * rather than emitted in visitation order, field order is fixed here, line endings are always
 * `\n`, and the output is pure ASCII so the bytes do not depend on the producer's default
 * charset.
 *
 * There is no decoder. The consumer is `udea-codegen`, which reads the file through its own
 * KSP processor options in a different module and a different process; a decoder here would be
 * a second implementation of a format with one reader.
 */
internal object KDocIndexJson {

    /** Bumped only when the shape changes incompatibly. */
    const val FORMAT_VERSION: Int = 1

    /**
     * Encodes [entries] in the pinned form.
     *
     * Sorting is by FQN and then by span, which is total: two declarations cannot share both.
     */
    fun encode(entries: Collection<KDocEntry>): String {
        val sorted = entries.sortedWith(ENTRY_ORDER)
        return buildString {
            append("{\n")
            append("  \"version\": ").append(FORMAT_VERSION).append(",\n")
            append("  \"entries\": ")
            if (sorted.isEmpty()) {
                append("[]\n")
            } else {
                append("[\n")
                sorted.forEachIndexed { index, entry ->
                    appendEntry(entry)
                    append(if (index == sorted.lastIndex) "\n" else ",\n")
                }
                append("  ]\n")
            }
            append("}\n")
        }
    }

    private val ENTRY_ORDER: Comparator<KDocEntry> = compareBy(
        { it.fqn },
        { it.span.path },
        { it.span.startLine },
        { it.span.startColumn },
    )

    private fun StringBuilder.appendEntry(entry: KDocEntry) {
        append("    {\n")
        append("      \"fqn\": ").appendJsonString(entry.fqn).append(",\n")
        append("      \"span\": ").appendJsonString(entry.span.toString()).append(",\n")
        append("      \"summary\": ").appendJsonString(entry.doc.summary).append(",\n")
        append("      \"params\": ")
        appendList(entry.doc.params) { param ->
            append("{\"name\": ").appendJsonString(param.name)
            append(", \"text\": ").appendJsonString(param.text).append("}")
        }
        append(",\n")
        append("      \"tags\": ")
        appendList(entry.doc.tags) { tag ->
            append("{\"tag\": ").appendJsonString(tag.tag)
            append(", \"text\": ").appendJsonString(tag.text).append("}")
        }
        append("\n    }")
    }

    private fun <T> StringBuilder.appendList(items: List<T>, appendItem: StringBuilder.(T) -> Unit) {
        if (items.isEmpty()) {
            append("[]")
            return
        }
        append("[\n")
        items.forEachIndexed { index, item ->
            append("        ")
            appendItem(item)
            append(if (index == items.lastIndex) "\n" else ",\n")
        }
        append("      ]")
    }

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
                char < ' ' || char > '~' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        return append('"')
    }
}
