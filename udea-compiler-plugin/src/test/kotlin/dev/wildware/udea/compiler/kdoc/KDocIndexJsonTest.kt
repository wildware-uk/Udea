package dev.wildware.udea.compiler.kdoc

import dev.wildware.udea.diagnostics.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `kdoc-index.json`'s encoding, which issue #42 requires to be byte-identical between two
 * clean builds.
 *
 * Every degree of freedom an encoder normally leaves open is pinned: ordering, line endings,
 * escaping and the charset the bytes land in.
 */
class KDocIndexJsonTest {

    private fun entry(fqn: String, line: Int = 1, doc: KDocBlock = KDocBlock("s", emptyList(), emptyList())) =
        KDocEntry(fqn, SourceSpan("moba/src/Ability.kt", line, 1, line, 1), doc)

    @Test
    fun `entries are sorted by fqn regardless of the order they were collected in`() {
        val forward = KDocIndexJson.encode(listOf(entry("a.B"), entry("a.A"), entry("a.C")))
        val backward = KDocIndexJson.encode(listOf(entry("a.C"), entry("a.B"), entry("a.A")))

        assertEquals(forward, backward)
        assertTrue(forward.indexOf("a.A") < forward.indexOf("a.B"), forward)
    }

    @Test
    fun `two entries with the same fqn are ordered by span`() {
        val encoded = KDocIndexJson.encode(listOf(entry("a.A", line = 9), entry("a.A", line = 2)))

        assertTrue(encoded.indexOf("Ability.kt:2:1") < encoded.indexOf("Ability.kt:9:1"), encoded)
    }

    @Test
    fun `the document uses newline endings and ends with exactly one`() {
        val encoded = KDocIndexJson.encode(listOf(entry("a.A")))

        assertTrue('\r' !in encoded, "a CRLF would differ between a Windows and a Linux producer")
        assertTrue(encoded.endsWith("}\n") && !encoded.endsWith("}\n\n"), encoded)
    }

    @Test
    fun `output is pure ASCII, so the bytes do not depend on the producer's charset`() {
        val encoded = KDocIndexJson.encode(
            listOf(entry("a.A", doc = KDocBlock("dégâts — —", emptyList(), emptyList()))),
        )

        assertTrue(encoded.all { it.code in 0x20..0x7e || it == '\n' }, encoded)
        assertTrue("\\u00e9" in encoded, encoded)
    }

    @Test
    fun `quotes, backslashes and newlines are escaped`() {
        val encoded = KDocIndexJson.encode(
            listOf(entry("a.A", doc = KDocBlock("a \"b\" \\ c\nd", emptyList(), emptyList()))),
        )

        assertTrue("""a \"b\" \\ c\nd""" in encoded, encoded)
    }

    @Test
    fun `an empty index is still a valid document with a version`() {
        assertEquals(
            "{\n  \"version\": ${KDocIndexJson.FORMAT_VERSION},\n  \"entries\": []\n}\n",
            KDocIndexJson.encode(emptyList()),
        )
    }

    @Test
    fun `params and tags are written in source order`() {
        val encoded = KDocIndexJson.encode(
            listOf(
                entry(
                    "a.A",
                    doc = KDocBlock(
                        summary = "Summary.",
                        params = listOf(KDocParam("second", "2"), KDocParam("first", "1")),
                        tags = listOf(KDocTag("return", "r"), KDocTag("see", "s")),
                    ),
                ),
            ),
        )

        // Source order, not alphabetical: a generated builder's parameters have to line up
        // with the constructor they came from.
        assertTrue(encoded.indexOf("\"second\"") < encoded.indexOf("\"first\""), encoded)
        assertTrue(encoded.indexOf("\"return\"") < encoded.indexOf("\"see\""), encoded)
    }
}
