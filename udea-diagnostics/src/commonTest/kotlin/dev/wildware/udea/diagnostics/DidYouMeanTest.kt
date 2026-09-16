package dev.wildware.udea.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DidYouMeanTest {

    private val assetIds = listOf(
        "character/orc",
        "character/elf",
        "character/priest",
        "character/soldier",
        "tile/grass",
        "ability/fireball",
    )

    @Test
    fun `a transposed-out letter in an asset id suggests the real id`() {
        assertEquals("character/orc", DidYouMean.suggest("charater/orc", assetIds))
    }

    @Test
    fun `a swapped pair suggests the real id`() {
        assertEquals("character/priest", DidYouMean.suggest("character/preist", assetIds))
    }

    @Test
    fun `a wrong-case id suggests the correctly cased id`() {
        assertEquals("ability/fireball", DidYouMean.suggest("Ability/Fireball", assetIds))
    }

    @Test
    fun `a doubled character suggests the real id`() {
        assertEquals("character/soldier", DidYouMean.suggest("character/solldier", assetIds))
    }

    @Test
    fun `nothing plausible suggests nothing`() {
        assertNull(DidYouMean.suggest("weapon/greataxe", assetIds))
        assertNull(DidYouMean.suggest("", assetIds))
    }

    @Test
    fun `a short candidate does not match an unrelated short entry`() {
        // Three edits turn any three-letter word into any other, so the budget must scale.
        assertNull(DidYouMean.suggest("elf", listOf("orc")))
        assertEquals(1, DidYouMean.defaultMaxDistance("orc"))
        assertEquals(2, DidYouMean.defaultMaxDistance("priest"))
        assertEquals(3, DidYouMean.defaultMaxDistance("character/orc"))
    }

    @Test
    fun `ties break lexicographically and not by iteration order`() {
        val tied = listOf("orc", "ore", "orb")
        assertEquals("orb", DidYouMean.suggest("orx", tied))
        assertEquals("orb", DidYouMean.suggest("orx", tied.reversed()))
        assertEquals("orb", DidYouMean.suggest("orx", tied.sorted()))
        assertEquals("orb", DidYouMean.suggest("orx", tied.sortedDescending()))
        assertEquals("orb", DidYouMean.suggest("orx", tied.toSet()))
    }

    @Test
    fun `a closer entry beats a lexicographically earlier one`() {
        // "aaaa" is alphabetically first, is seen first, and is inside the budget at three
        // edits - but "beto" is one edit away, so distance must outrank the tie-break.
        val known = listOf("aaaa", "beto")
        assertEquals("beto", DidYouMean.suggest("beta", known, maxDistance = 3))
    }

    @Test
    fun `an exact match is its own suggestion`() {
        assertEquals("character/orc", DidYouMean.suggest("character/orc", assetIds))
    }

    @Test
    fun `an explicit budget overrides the length-scaled default`() {
        assertNull(DidYouMean.suggest("charater/orc", assetIds, maxDistance = 0))
        assertEquals("character/orc", DidYouMean.suggest("charater/orc", assetIds, maxDistance = 1))
        assertFailsWith<IllegalArgumentException> {
            DidYouMean.suggest("charater/orc", assetIds, maxDistance = -1)
        }
    }

    @Test
    fun `levenshtein distance is the textbook edit distance`() {
        assertEquals(0, DidYouMean.distance("orc", "orc"))
        assertEquals(3, DidYouMean.distance("kitten", "sitting"))
        assertEquals(1, DidYouMean.distance("charater", "character"))
        assertEquals(4, DidYouMean.distance("", "orcs"))
        assertEquals(4, DidYouMean.distance("orcs", ""))
        // distance() is case-sensitive; only suggest() folds case.
        assertEquals(1, DidYouMean.distance("Orc", "orc"))
        // Symmetric.
        assertEquals(
            DidYouMean.distance("character/orc", "charater/orc"),
            DidYouMean.distance("charater/orc", "character/orc"),
        )
    }
}
