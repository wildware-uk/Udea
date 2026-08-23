package dev.wildware.udea.codegen.agent

/**
 * A JSON reader for the tests, so the generated documents are checked by *parsing* them rather
 * than by matching text.
 *
 * The distinction matters: substring assertions pass on a document that is not valid JSON at
 * all, and the bridge's answer to a manifest it cannot parse is to silently fall back to no
 * manifest — an agent then sees none of the game's tools with nothing anywhere reporting why.
 * So the tests parse.
 *
 * Written here rather than pulled in as a dependency because `udea-codegen` deliberately has
 * none beyond KSP and KotlinPoet, and a parser strict enough to catch the mistakes that matter
 * (an unterminated string, a trailing comma, an unescaped control character) is short.
 */
internal object TestJson {

    /** Parses [text], or throws with the offset. Trailing content is an error, not ignored. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        require(reader.atEnd) { "trailing content at offset ${reader.offset} in: $text" }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?>
        ?: error("expected a JSON object, got ${value?.javaClass?.simpleName}: $value")

    fun arr(value: Any?): List<Any?> = value as? List<Any?>
        ?: error("expected a JSON array, got ${value?.javaClass?.simpleName}: $value")

    /** True for the three JSON scalar kinds; the digest's `game` block may hold nothing else. */
    fun isScalar(value: Any?): Boolean =
        value is String || value is Double || value is Boolean || value == null

    private class Reader(private val text: String) {
        var offset: Int = 0

        val atEnd: Boolean get() = offset >= text.length

        fun skipWhitespace() {
            while (!atEnd && text[offset].isWhitespace()) offset++
        }

        fun value(): Any? {
            skipWhitespace()
            require(!atEnd) { "unexpected end of input" }
            return when (text[offset]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val members = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                offset++
                return members
            }
            while (true) {
                skipWhitespace()
                val key = string()
                require(key !in members) { "duplicate key '$key' at offset $offset" }
                skipWhitespace()
                expect(':')
                members[key] = value()
                skipWhitespace()
                when (val next = peek()) {
                    ',' -> offset++
                    '}' -> {
                        offset++
                        return members
                    }
                    else -> error("expected ',' or '}' at offset $offset, got '$next'")
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val elements = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                offset++
                return elements
            }
            while (true) {
                elements += value()
                skipWhitespace()
                when (val next = peek()) {
                    ',' -> offset++
                    ']' -> {
                        offset++
                        return elements
                    }
                    else -> error("expected ',' or ']' at offset $offset, got '$next'")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                require(!atEnd) { "unterminated string" }
                val character = text[offset++]
                when {
                    character == '"' -> return builder.toString()
                    character == '\\' -> builder.append(escape())
                    // The reason this parser is strict: a raw control character is exactly what
                    // an unescaped tab in a KDoc description would put here, and it makes the
                    // whole manifest unparseable.
                    character < ' ' -> error("unescaped control character U+%04X at offset $offset".format(character.code))
                    else -> builder.append(character)
                }
            }
        }

        private fun escape(): Char {
            val character = text[offset++]
            return when (character) {
                '"', '\\', '/' -> character
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> text.substring(offset, offset + 4).toInt(16).toChar().also { offset += 4 }
                else -> error("bad escape '\\$character' at offset $offset")
            }
        }

        private fun number(): Double {
            val start = offset
            while (!atEnd && (text[offset].isDigit() || text[offset] in "-+.eE")) offset++
            return text.substring(start, offset).toDoubleOrNull()
                ?: error("bad number '${text.substring(start, offset)}' at offset $start")
        }

        private fun <T> literal(spelling: String, value: T): T {
            require(text.startsWith(spelling, offset)) { "bad literal at offset $offset" }
            offset += spelling.length
            return value
        }

        private fun peek(): Char = if (atEnd) error("unexpected end of input") else text[offset]

        private fun expect(character: Char) {
            require(peek() == character) { "expected '$character' at offset $offset" }
            offset++
        }
    }
}
