package dev.wildware.udea.diagnostics.assets

/**
 * The outcome of decoding one `META-INF/udea/asset-index.json`.
 *
 * A sealed result rather than an exception because two of the three cases are things the
 * reader must *report*, not crash on, and the third is the common one.
 */
public sealed interface AssetCatalogDecode {

    /** Decoded cleanly. */
    public data class Ok(public val catalog: AssetCatalog) : AssetCatalogDecode

    /**
     * The document declares a format version this build does not know.
     *
     * Issue #40 requires this to be loud rather than silently ignored: a catalog read as empty
     * because its version moved would turn every `reference("...")` in the project into a
     * silently unvalidated string, which is the status quo this work exists to end.
     */
    public data class VersionMismatch(
        public val found: Int,
        public val expected: Int = AssetCatalog.FORMAT_VERSION,
    ) : AssetCatalogDecode

    /** The document is not the shape this format defines. [reason] names the first problem. */
    public data class Malformed(public val reason: String) : AssetCatalogDecode
}

/**
 * The `META-INF/udea/asset-index.json` wire format: encode, decode, and nothing else.
 *
 * ### Why the encoding is pinned rather than conventional
 *
 * Issue #90 makes this resource part of a cache-correct Gradle task, and issue #40 requires
 * two runs from the same input to be byte-identical. So every degree of freedom is fixed the
 * same way `DiagnosticsJson` fixes them, for the same reason:
 *
 * - **Entry order** is [AssetCatalogEntry]'s own total order, not a map iteration order.
 * - **Key order** inside an object is fixed by this object.
 * - **Line endings** are always `\n` and the document ends with exactly one.
 * - **Indentation** is two spaces.
 * - **Output is pure ASCII**: anything outside printable ASCII is escaped `\uXXXX`, so the
 *   bytes do not depend on a producer's default charset.
 * - **No timestamps, no paths, no producer name.** Nothing in the document varies with when
 *   or where it was built.
 *
 * Unlike `DiagnosticsJson` this one *does* have a decoder, because the whole point of the
 * resource is that a different process — the K2 compiler plugin, inside the IDE's analysis
 * session — reads it back.
 *
 * ### Why the parser is hand-written
 *
 * `ModuleGraphTest` holds this module to the Kotlin stdlib and nothing else, and the reason it
 * does is that this module sits on the compile classpath of the compiler plugin, the KSP
 * processor and the runtime simultaneously: a serialization library here is a library in the
 * Kotlin compiler's own classloader. The grammar below is the JSON subset this format emits,
 * plus enough tolerance (any whitespace, any key order, unknown keys skipped) that a
 * hand-edited file behaves.
 */
public object AssetCatalogJson {

    /** Encodes [catalog] in the pinned form described on [AssetCatalogJson]. */
    public fun encode(catalog: AssetCatalog): String = buildString {
        append("{\n")
        append("  \"version\": ").append(AssetCatalog.FORMAT_VERSION).append(",\n")
        append("  \"assets\": ")
        if (catalog.entries.isEmpty()) {
            append("[]\n")
        } else {
            append("[\n")
            catalog.entries.forEachIndexed { index, entry ->
                append("    {\"id\": ").appendJsonString(entry.id)
                append(", \"kind\": ").appendJsonString(entry.kindFqn).append("}")
                append(if (index == catalog.entries.lastIndex) "\n" else ",\n")
            }
            append("  ]\n")
        }
        append("}\n")
    }

    /** Decodes [text]. Never throws: every failure is an [AssetCatalogDecode] case. */
    public fun decode(text: String): AssetCatalogDecode {
        val root = when (val parsed = Json.parse(text)) {
            is Json.Result.Failure -> return AssetCatalogDecode.Malformed(parsed.reason)
            is Json.Result.Success -> parsed.value
        }
        if (root !is Map<*, *>) {
            return AssetCatalogDecode.Malformed("the document root is not a JSON object")
        }
        val version = root["version"]
        if (version !is Long) {
            return AssetCatalogDecode.Malformed(
                "\"version\" is missing or not an integer (was ${describe(version)})",
            )
        }
        if (version != AssetCatalog.FORMAT_VERSION.toLong()) {
            return AssetCatalogDecode.VersionMismatch(found = version.toInt())
        }
        val assets = root["assets"]
        if (assets !is List<*>) {
            return AssetCatalogDecode.Malformed(
                "\"assets\" is missing or not an array (was ${describe(assets)})",
            )
        }
        val entries = ArrayList<AssetCatalogEntry>(assets.size)
        for ((index, element) in assets.withIndex()) {
            if (element !is Map<*, *>) {
                return AssetCatalogDecode.Malformed("assets[$index] is not an object")
            }
            val id = element["id"]
            val kind = element["kind"]
            if (id !is String || id.isBlank()) {
                return AssetCatalogDecode.Malformed("assets[$index].id is missing or blank")
            }
            if (kind !is String || kind.isBlank()) {
                return AssetCatalogDecode.Malformed("assets[$index].kind is missing or blank")
            }
            entries += AssetCatalogEntry(id, kind)
        }
        return AssetCatalogDecode.Ok(AssetCatalog.of(entries))
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "absent or null"
        is String -> "a string"
        is Long, is Double -> "a number"
        is Boolean -> "a boolean"
        is List<*> -> "an array"
        is Map<*, *> -> "an object"
        else -> "unknown"
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
                char < ' ' || char > '~' -> append("\\u").append(char.code.toHex4())
                else -> append(char)
            }
        }
        return append('"')
    }

    private fun Int.toHex4(): String = toString(16).padStart(4, '0')

    /**
     * A minimal recursive-descent JSON reader.
     *
     * Deliberately generic rather than tailored to [AssetCatalogJson]'s exact shape: a parser
     * that only accepts the bytes its own encoder produced cannot report *why* a hand-edited
     * or half-written file is wrong, and "malformed" with no position is the diagnostic
     * section 5 of the engineering standards exists to forbid.
     *
     * Numbers decode to `Long` when integral and `Double` otherwise; this format only ever
     * carries one integer.
     */
    private object Json {

        /**
         * U+000C, the one JSON escape Kotlin has no character escape of its own for.
         *
         * A named constant rather than a literal because a bare form feed in a source file
         * is invisible in every diff and every review tool.
         */
        val FORM_FEED: Char = 12.toChar()

        sealed interface Result {
            data class Success(val value: Any?) : Result
            data class Failure(val reason: String) : Result
        }

        fun parse(text: String): Result {
            val reader = Reader(text)
            return try {
                reader.skipWhitespace()
                val value = reader.readValue()
                reader.skipWhitespace()
                if (!reader.atEnd()) {
                    Result.Failure("trailing content at offset ${reader.offset}")
                } else {
                    Result.Success(value)
                }
            } catch (failure: JsonFailure) {
                Result.Failure(failure.reason)
            }
        }

        private class JsonFailure(val reason: String) : Exception(reason)

        private class Reader(private val text: String) {
            var offset: Int = 0
                private set

            fun atEnd(): Boolean = offset >= text.length

            fun skipWhitespace() {
                while (offset < text.length && text[offset].isJsonSpace()) offset++
            }

            fun readValue(): Any? {
                if (atEnd()) fail("unexpected end of document")
                return when (val char = text[offset]) {
                    '{' -> readObject()
                    '[' -> readArray()
                    '"' -> readString()
                    't' -> readKeyword("true", true)
                    'f' -> readKeyword("false", false)
                    'n' -> readKeyword("null", null)
                    else ->
                        if (char == '-' || char.isDigit()) readNumber()
                        else fail("unexpected character '$char' at offset $offset")
                }
            }

            private fun readObject(): Map<String, Any?> {
                expect('{')
                val result = LinkedHashMap<String, Any?>()
                skipWhitespace()
                if (peek() == '}') {
                    offset++
                    return result
                }
                while (true) {
                    skipWhitespace()
                    val key = readString()
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    result[key] = readValue()
                    skipWhitespace()
                    when (val char = peek()) {
                        ',' -> offset++
                        '}' -> {
                            offset++
                            return result
                        }
                        else -> fail("expected ',' or '}' at offset $offset, got '$char'")
                    }
                }
            }

            private fun readArray(): List<Any?> {
                expect('[')
                val result = ArrayList<Any?>()
                skipWhitespace()
                if (peek() == ']') {
                    offset++
                    return result
                }
                while (true) {
                    skipWhitespace()
                    result += readValue()
                    skipWhitespace()
                    when (val char = peek()) {
                        ',' -> offset++
                        ']' -> {
                            offset++
                            return result
                        }
                        else -> fail("expected ',' or ']' at offset $offset, got '$char'")
                    }
                }
            }

            private fun readString(): String {
                expect('"')
                val builder = StringBuilder()
                while (true) {
                    if (atEnd()) fail("unterminated string")
                    when (val char = text[offset++]) {
                        '"' -> return builder.toString()
                        '\\' -> builder.append(readEscape())
                        else -> builder.append(char)
                    }
                }
            }

            private fun readEscape(): Char {
                if (atEnd()) fail("unterminated escape")
                return when (val char = text[offset++]) {
                    '"', '\\', '/' -> char
                    'b' -> '\b'
                    'f' -> FORM_FEED
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        if (offset + 4 > text.length) fail("truncated \\u escape at offset $offset")
                        val hex = text.substring(offset, offset + 4)
                        val code = hex.toIntOrNull(16) ?: fail("bad \\u escape '$hex'")
                        offset += 4
                        code.toChar()
                    }
                    else -> fail("unknown escape '\\$char' at offset ${offset - 1}")
                }
            }

            private fun readNumber(): Any {
                val start = offset
                if (peek() == '-') offset++
                while (offset < text.length && text[offset].isNumberChar()) offset++
                val literal = text.substring(start, offset)
                return literal.toLongOrNull()
                    ?: literal.toDoubleOrNull()
                    ?: fail("'$literal' at offset $start is not a number")
            }

            private fun readKeyword(keyword: String, value: Any?): Any? {
                if (!text.startsWith(keyword, offset)) {
                    fail("expected '$keyword' at offset $offset")
                }
                offset += keyword.length
                return value
            }

            private fun peek(): Char = if (atEnd()) fail("unexpected end of document") else text[offset]

            private fun expect(char: Char) {
                if (peek() != char) fail("expected '$char' at offset $offset, got '${text[offset]}'")
                offset++
            }

            private fun fail(reason: String): Nothing = throw JsonFailure(reason)

            private fun Char.isJsonSpace(): Boolean =
                this == ' ' || this == '\t' || this == '\n' || this == '\r'

            private fun Char.isNumberChar(): Boolean =
                isDigit() || this == '.' || this == 'e' || this == 'E' || this == '+' || this == '-'
        }
    }
}
