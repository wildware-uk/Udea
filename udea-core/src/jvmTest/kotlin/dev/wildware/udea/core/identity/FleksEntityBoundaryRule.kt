package dev.wildware.udea.core.identity

import dev.wildware.udea.core.KotlinSource

/**
 * The rule behind [NoFleksEntityAcrossBoundariesTest].
 *
 * A Fleks `Entity` is a slot index into one world in one process at one moment. Three
 * boundaries must never carry one: the wire (`@Net`), the snapshot ring (`@Net`/`@Sim`) and
 * the agent tool surface (`@AgentTool`). `common/network/packets.kt` crossed the first of
 * them — `EntityCreate.entity` shipped a raw `Entity` — and the symptom was one machine's
 * slot index being read as another machine's.
 *
 * The check is over source rather than types because the annotations that mark these
 * boundaries are what a compiler is perfectly happy to put on an `Entity`-typed field. It
 * runs over this module today and is written to be lifted into the repo-wide verifier when
 * `@Net`/`@Sim`/`@AgentTool` and the modules that use them exist.
 */
internal object FleksEntityBoundaryRule {

    internal data class Violation(
        val path: String,
        val line: Int,
        val annotation: String,
        val declaration: String,
    ) {
        override fun toString(): String = "$path:$line  @$annotation  ${declaration.trim()}"
    }

    private val BOUNDARY_ANNOTATION = Regex("""@(Net|Sim|AgentTool)\b""")
    private val FLEKS_ENTITY = Regex("""\bEntity\b""")

    /** Every boundary declaration in [source] whose type mentions a bare `Entity`. */
    fun violations(path: String, source: String): List<Violation> {
        // Comments and strings are blanked first: this module's own KDoc discusses `@Net`
        // and `Entity` at length, and prose is not a declaration.
        val code = KotlinSource.stripCommentsAndStrings(source)

        return BOUNDARY_ANNOTATION.findAll(code).mapNotNull { match ->
            val declaration = declarationAt(code, match.range.last + 1)
            if (FLEKS_ENTITY.containsMatchIn(declaration)) {
                Violation(
                    path = path,
                    line = KotlinSource.lineOf(code, match.range.first),
                    annotation = match.groupValues[1],
                    declaration = declaration,
                )
            } else {
                null
            }
        }.toList()
    }

    /**
     * The declaration an annotation ending at [from] is attached to.
     *
     * Skips the annotation's own argument list and any further annotations — `@Net @Q(bits =
     * 12) var rotation` is one declaration, and `@AgentTool("damage")` usually sits on the
     * line above its `fun`. Then runs to the end of the declaration, where an unclosed
     * bracket carries the scan onto the next line so a multi-line parameter list is read
     * whole, and a `{` outside brackets ends it so a function body is never scanned.
     */
    private fun declarationAt(code: String, from: Int): String {
        var cursor = skipAnnotations(code, from)
        val start = cursor
        var depth = 0
        while (cursor < code.length) {
            when (code[cursor]) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                '{', '\n' -> if (depth == 0) return code.substring(start, cursor)
            }
            cursor++
        }
        return code.substring(start)
    }

    /** Advances past this annotation's arguments and any annotations that follow it. */
    private fun skipAnnotations(code: String, from: Int): Int {
        var cursor = from
        while (true) {
            cursor = skipBalancedParens(code, cursor)
            val next = skipWhitespace(code, cursor)
            if (next < code.length && code[next] == '@') {
                cursor = next + 1
                while (cursor < code.length && (code[cursor].isLetterOrDigit() || code[cursor] == '_' || code[cursor] == '.')) {
                    cursor++
                }
            } else {
                return next
            }
        }
    }

    private fun skipBalancedParens(code: String, from: Int): Int {
        if (from >= code.length || code[from] != '(') return from
        var depth = 0
        var cursor = from
        while (cursor < code.length) {
            when (code[cursor]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return cursor + 1
                }
            }
            cursor++
        }
        return cursor
    }

    private fun skipWhitespace(code: String, from: Int): Int {
        var cursor = from
        while (cursor < code.length && code[cursor].isWhitespace()) cursor++
        return cursor
    }
}
