package dev.wildware.udea.assets.compiler.pipeline

import dev.wildware.udea.assets.compiler.scan.Declaration
import dev.wildware.udea.diagnostics.SourceSpan
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Reads back what [dev.wildware.udea.assets.compiler.scan.DeclarationsJson] wrote.
 *
 * ## Why a reader exists at all
 *
 * `udeaScanAssets` and `udeaGenerateAccessors` are two tasks because they have different inputs:
 * the scan reads `.udea.kts` and the accessors read the scan. Gradle can only make that split
 * pay - one task up to date while the other reruns, and both relocatable in the build cache -
 * if the boundary between them is a *file*. So the scan's output has to be readable, and this
 * is the half that was missing.
 *
 * ## Why it is hand-written
 *
 * The same argument the writer makes: this is build tooling, the document is fifteen lines of
 * shape, and adding a JSON library to the module that compiles a game's assets is a dependency
 * on every consumer's classpath forever. The parser below is a complete JSON value parser
 * rather than a set of regexes, because a regex over `"id": "..."` breaks on the first asset id
 * containing a quote and does so silently.
 */
public object DeclarationsJsonReader {

    /** The declarations in the document at [file], in the order it stores them. */
    public fun read(file: Path): List<Declaration> = parse(file.readText(Charsets.UTF_8))

    /** The declarations in [text]. */
    public fun parse(text: String): List<Declaration> {
        val root = Json(text).value() as? Map<*, *>
            ?: error("declarations.json must be a JSON object")
        val declarations = root["declarations"] as? List<*>
            ?: error("declarations.json has no 'declarations' array")
        return declarations.map { entry ->
            val fields = entry as? Map<*, *> ?: error("a declarations entry is not an object")
            fun string(name: String): String = fields[name] as? String
                ?: error("a declarations entry has no string '$name'")

            fun int(name: String): Int = (fields[name] as? Double)?.toInt()
                ?: error("a declarations entry has no number '$name'")
            Declaration(
                kind = string("kind"),
                id = string("id"),
                name = string("name"),
                span = SourceSpan(
                    string("file"),
                    int("startLine"),
                    int("startColumn"),
                    int("endLine"),
                    int("endColumn"),
                ),
            )
        }
    }

    /**
     * A JSON reader over one string: objects, arrays, strings, numbers, booleans and null.
     *
     * Numbers all come back as `Double` - the document holds line and column numbers, which are
     * exactly representable - and every failure names the offset, because a build tool that says
     * only "invalid JSON" about a file it generated itself is a build tool nobody can debug.
     */
    private class Json(private val text: String) {

        private var at = 0

        fun value(): Any? {
            skipSpace()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("unexpected '$c'")
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val entries = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                at++
                return entries
            }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                expect(':')
                entries[key] = value()
                skipSpace()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return entries
                    else -> fail("expected ',' or '}' but found '$c'")
                }
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val items = mutableListOf<Any?>()
            skipSpace()
            if (peek() == ']') {
                at++
                return items
            }
            while (true) {
                items += value()
                skipSpace()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return items
                    else -> fail("expected ',' or ']' but found '$c'")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return out.toString()
                    '\\' -> when (val escape = next()) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = text.substring(at, at + 4)
                            at += 4
                            out.append(hex.toInt(16).toChar())
                        }
                        else -> fail("unknown escape '\\$escape'")
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            return text.substring(start, at).toDoubleOrNull() ?: fail("'${text.substring(start, at)}' is not a number")
        }

        private fun <T> literal(word: String, value: T): T {
            if (!text.startsWith(word, at)) fail("expected '$word'")
            at += word.length
            return value
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(): Char = if (at < text.length) text[at] else fail("unexpected end of document")

        private fun next(): Char = peek().also { at++ }

        private fun expect(c: Char) {
            if (next() != c) fail("expected '$c'")
        }

        private fun fail(problem: String): Nothing =
            error("declarations.json at offset $at: $problem")
    }
}
