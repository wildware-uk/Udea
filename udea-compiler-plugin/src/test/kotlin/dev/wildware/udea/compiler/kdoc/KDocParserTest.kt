package dev.wildware.udea.compiler.kdoc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half of the KDoc harvester that holds the decisions: what is kept, what is dropped, and
 * what the text of a kept tag is.
 */
class KDocParserTest {

    private fun parse(text: String): KDocBlock =
        requireNotNull(KDocParser.parse(text.trimIndent())) { "expected a KDoc block" }

    @Test
    fun `the summary keeps its line breaks and loses its margin`() {
        val block = parse(
            """
            /**
             * One activatable ability.
             *
             * Cooldown is denominated in ticks.
             */
            """,
        )

        assertEquals("One activatable ability.\n\nCooldown is denominated in ticks.", block.summary)
        assertEquals(emptyList(), block.params)
        assertEquals(emptyList(), block.tags)
    }

    @Test
    fun `a param tag keeps its name and its text verbatim`() {
        val block = parse(
            """
            /**
             * An ability.
             *
             * @param cooldown ticks before it may be activated again.
             * @param manaCost mana removed on activation, before reductions.
             */
            """,
        )

        assertEquals(
            listOf(
                KDocParam("cooldown", "ticks before it may be activated again."),
                KDocParam("manaCost", "mana removed on activation, before reductions."),
            ),
            block.params,
        )
        assertEquals("An ability.", block.summary)
    }

    @Test
    fun `a tag's continuation lines belong to that tag, not to the summary`() {
        val block = parse(
            """
            /**
             * An ability.
             *
             * @param cooldown ticks before it may be activated again,
             *   measured from the moment the effect ends.
             */
            """,
        )

        assertEquals(
            "ticks before it may be activated again,\n  measured from the moment the effect ends.",
            block.params.single().text,
        )
        assertEquals("An ability.", block.summary)
    }

    @Test
    fun `return, see and throws survive and everything else is dropped`() {
        val block = parse(
            """
            /**
             * Summary.
             *
             * @return the resolved ability.
             * @see Abilities
             * @throws IllegalStateException when the ability is not registered.
             * @sample dev.wildware.udea.samples.abilityUsage
             * @suppress
             */
            """,
        )

        assertEquals(
            listOf(
                KDocTag("return", "the resolved ability."),
                KDocTag("see", "Abilities"),
                KDocTag("throws", "IllegalStateException when the ability is not registered."),
            ),
            block.tags,
        )
    }

    @Test
    fun `an unsupported tag swallows its own continuation lines`() {
        // Otherwise the dropped tag's prose would silently reappear at the end of the summary,
        // which is the "malformed KDoc in a generated file" issue #42 forbids.
        val block = parse(
            """
            /**
             * Summary.
             *
             * @sample dev.wildware.udea.samples.abilityUsage
             *   and some more text about the sample
             */
            """,
        )

        assertEquals("Summary.", block.summary)
        assertEquals(emptyList(), block.tags)
    }

    @Test
    fun `a one-line KDoc is a summary`() {
        assertEquals("Ticks since spawn.", parse("/** Ticks since spawn. */").summary)
    }

    @Test
    fun `a comment with nothing in it parses to an empty block rather than to null`() {
        // "no doc comment" and "a doc comment with nothing worth keeping" are different facts,
        // and the harvester writes an entry for neither - but only one of them is a parse
        // failure worth reporting.
        val block = parse("/**\n *\n */")

        assertTrue(block.isEmpty)
    }

    @Test
    fun `a param with no name is dropped rather than indexed under an empty key`() {
        val block = parse(
            """
            /**
             * Summary.
             *
             * @param
             */
            """,
        )

        assertEquals(emptyList(), block.params)
    }

    @Test
    fun `text that is not a KDoc comment is not parsed`() {
        assertNull(KDocParser.parse("// a line comment"))
        assertNull(KDocParser.parse("/* an ordinary block comment */"))
        assertNull(KDocParser.parse("/** unterminated"))
    }
}
