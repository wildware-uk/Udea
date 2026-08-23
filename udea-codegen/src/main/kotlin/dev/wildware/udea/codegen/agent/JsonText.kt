package dev.wildware.udea.codegen.agent

/**
 * The smallest JSON writer that can produce the two agent-facing documents this generator
 * emits: a tool's `inputSchema` and the module's manifest fragment.
 *
 * ## Why not a JSON library
 *
 * `udea-codegen` runs inside the Kotlin compiler, on every consumer's annotation-processor
 * classpath. A serialisation library there is a version conflict waiting for the first
 * consumer that has its own, and the documents here are a fixed shape written once - three
 * value kinds and two containers. The cost of the library is real and permanent; the cost of
 * these forty lines is not.
 *
 * ## Ordering is by construction
 *
 * Every container is built from an ordered list, and nothing here sorts or hashes. Two builds
 * of the same sources produce byte-identical text, which is what lets the manifest be a
 * checked-in golden that CI diffs.
 */
internal object JsonText {

    /** A JSON value that already knows how to render itself. */
    sealed interface Value {
        fun render(builder: StringBuilder, indent: String)
    }

    /** A string, escaped for JSON. */
    data class Text(val value: String) : Value {
        override fun render(builder: StringBuilder, indent: String) {
            escape(builder, value)
        }
    }

    /** `true`, `false`, `null` or a number: written through verbatim. */
    data class Literal(val text: String) : Value {
        override fun render(builder: StringBuilder, indent: String) {
            builder.append(text)
        }
    }

    /** An object, rendered one key per line so a manifest diff is a line diff. */
    data class Obj(val members: List<Pair<String, Value>>) : Value {
        override fun render(builder: StringBuilder, indent: String) {
            if (members.isEmpty()) {
                builder.append("{}")
                return
            }
            val inner = "$indent  "
            builder.append("{\n")
            members.forEachIndexed { position, (key, value) ->
                builder.append(inner)
                escape(builder, key)
                builder.append(": ")
                value.render(builder, inner)
                if (position < members.size - 1) builder.append(',')
                builder.append('\n')
            }
            builder.append(indent).append('}')
        }
    }

    /** An array, one element per line for the same reason. */
    data class Arr(val elements: List<Value>) : Value {
        override fun render(builder: StringBuilder, indent: String) {
            if (elements.isEmpty()) {
                builder.append("[]")
                return
            }
            val inner = "$indent  "
            builder.append("[\n")
            elements.forEachIndexed { position, element ->
                builder.append(inner)
                element.render(builder, inner)
                if (position < elements.size - 1) builder.append(',')
                builder.append('\n')
            }
            builder.append(indent).append(']')
        }
    }

    /** Pretty JSON, newline-terminated, LF only so the golden diffs the same on every OS. */
    fun render(value: Value): String = StringBuilder().apply {
        value.render(this, "")
        append('\n')
    }.toString()

    /**
     * The same document on one line.
     *
     * A tool's `inputSchema` is carried as a Kotlin string constant in generated source, and a
     * multi-line literal there would be a multi-line diff for every unrelated edit.
     */
    fun renderCompact(value: Value): String {
        val pretty = render(value)
        val builder = StringBuilder(pretty.length)
        var inString = false
        var escaped = false
        for (character in pretty) {
            when {
                escaped -> escaped = false
                character == '\\' && inString -> escaped = true
                character == '"' -> inString = !inString
            }
            if (!inString && (character == '\n' || character == ' ')) continue
            builder.append(character)
        }
        return builder.toString()
    }

    /**
     * JSON string escaping, including the control characters below `0x20`.
     *
     * A raw control character in a description makes the whole manifest unparseable, and the
     * bridge's answer to an unparseable manifest is to fall back to no manifest at all - so a
     * tab in a KDoc line would silently cost an agent every tool the game has.
     */
    private fun escape(builder: StringBuilder, value: String) {
        builder.append('"')
        for (character in value) {
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> if (character < ' ') {
                    builder.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(character)
                }
            }
        }
        builder.append('"')
    }
}
