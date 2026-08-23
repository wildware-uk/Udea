package dev.wildware.udea.build

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `docs/migration/trello-map.md` accounts for every card spec section 9 names.
 *
 * Run against the real spec and the real map, so the committed files are what is asserted.
 */
class TrelloMapTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }

    private val spec = File(
        repoRoot,
        "docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md",
    ).readText()

    private val map = File(repoRoot, "docs/migration/trello-map.md").readText()

    @Test
    fun `every card in spec section 9 has a disposition in the committed map`() {
        assertEquals(emptyList(), TrelloMap.findings(spec, map))
    }

    @Test
    fun `the cards actually found are the ones section 9 lists`() {
        val cards = TrelloMap.cardsInSpec(spec)

        assertEquals(26, cards.size, cards.toString())
        assertTrue(cards.containsAll(listOf(5, 9, 10, 11, 14, 15, 28, 31, 35)), cards.toString())
    }

    @Test
    fun `a card section 9 names but the map omits fails, naming the card`() {
        val edited = map.replace("| #31 | Make Udea multiplatform |", "| Make Udea multiplatform |")

        val finding = TrelloMap.findings(spec, edited).single()

        assertEquals(TrelloMap.UNMAPPED_CARD, finding.rule)
        assertTrue(finding.message.contains("#31"), finding.message)
        assertEquals("docs/migration/trello-map.md", finding.path)
    }

    @Test
    fun `a GitHub issue citation does not count as covering a Trello card of the same number`() {
        // GitHub #14 and #15 are epics; Trello #14 and #15 are deferred cards. Only a first-cell
        // id counts, or a citation in a right-hand column would mark a card mapped.
        val stripped = map
            .replace("| #14 | Mod support |", "| Mod support |")
            .replace("| #15 | Safe script sandboxing |", "| Safe script sandboxing |")

        val cards = TrelloMap.findings(spec, stripped).map { it.message }

        assertEquals(2, cards.size, cards.toString())
        assertTrue(cards.any { it.contains("#14") } && cards.any { it.contains("#15") }, cards.toString())
    }

    @Test
    fun `the map may cover cards section 9 never mentioned`() {
        val extended = map + "\n| #99 | A card added to the board later | deferred |\n"

        assertEquals(emptyList(), TrelloMap.findings(spec, extended))
    }

    @Test
    fun `a spec with no section 9 is a hard failure, not a silent pass`() {
        assertFailsWith<IllegalArgumentException> {
            TrelloMap.cardsInSpec(spec.replace(TrelloMap.SPEC_SECTION, "## 9. Something else"))
        }
    }

    @Test
    fun `a section 9 naming no cards is a hard failure`() {
        val emptied = spec.substringBefore(TrelloMap.SPEC_SECTION) + TrelloMap.SPEC_SECTION + "\n\nNothing.\n"

        assertFailsWith<IllegalArgumentException> { TrelloMap.cardsInSpec(emptied) }
    }
}
