package dev.wildware.udea.core

/**
 * Just enough Kotlin lexing for the architecture rules in this module.
 *
 * The rules read source, so they have to distinguish code from prose. Without that, this
 * module's own KDoc — which discusses `@Net`, `@Sim` and `lateinit var` at length — would
 * trip every rule it documents.
 */
internal object KotlinSource {

    /**
     * Replaces comments and string literals with spaces, preserving every offset and every
     * newline so line numbers still line up with the original file.
     */
    fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0

        fun blankTo(end: Int) {
            while (index < end && index < source.length) {
                out.append(if (source[index] == '\n') '\n' else ' ')
                index++
            }
        }

        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when {
                char == '/' && next == '/' -> {
                    val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                    blankTo(end)
                }

                char == '/' && next == '*' -> {
                    var depth = 1
                    var cursor = index + 2
                    while (cursor < source.length && depth > 0) {
                        if (source.startsWith("/*", cursor)) {
                            depth++
                            cursor += 2
                        } else if (source.startsWith("*/", cursor)) {
                            depth--
                            cursor += 2
                        } else {
                            cursor++
                        }
                    }
                    blankTo(cursor)
                }

                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3)
                    blankTo(if (end < 0) source.length else end + 3)
                }

                char == '"' -> {
                    var cursor = index + 1
                    while (cursor < source.length && source[cursor] != '"' && source[cursor] != '\n') {
                        cursor += if (source[cursor] == '\\') 2 else 1
                    }
                    blankTo(minOf(cursor + 1, source.length))
                }

                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    /** 1-based line number of [offset] in [source]. */
    fun lineOf(source: String, offset: Int): Int =
        source.substring(0, offset.coerceAtMost(source.length)).count { it == '\n' } + 1
}
