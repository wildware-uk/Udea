package dev.wildware.udea.diagnostics.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The merge, the lookup and the did-you-mean.
 *
 * Ids are the real ones from `example/src/main/resources/assets`, because a fixture invented
 * for a test proves the algorithm and not the thing an author will actually type.
 */
class AssetCatalogTest {

    private val orc = AssetCatalogEntry("character/orc", CHARACTER)
    private val arrow = AssetCatalogEntry("blueprint/arrow", BLUEPRINT)

    @Test
    fun `entries are sorted and deduplicated`() {
        val catalog = AssetCatalog.of(listOf(orc, arrow, orc))

        assertEquals(listOf(arrow, orc), catalog.entries)
        assertEquals(emptyList(), catalog.conflicts)
    }

    @Test
    fun `two module catalogs merge into one, sorted, deduplicated`() {
        val upstream = AssetCatalog.of(listOf(orc))
        val downstream = AssetCatalog.of(listOf(arrow, orc))

        val merged = AssetCatalog.merge(listOf(upstream, downstream))

        assertEquals(listOf("blueprint/arrow", "character/orc"), merged.ids)
        assertEquals(listOf(arrow, orc), merged.entries)
        assertEquals(emptyList(), merged.conflicts)
    }

    @Test
    fun `an id declared with two kinds across modules is reported once`() {
        val upstream = AssetCatalog.of(listOf(orc))
        val downstream = AssetCatalog.of(listOf(AssetCatalogEntry("character/orc", BLUEPRINT)))

        val merged = AssetCatalog.merge(listOf(upstream, downstream, upstream))

        assertEquals(
            listOf(AssetCatalogConflict("character/orc", listOf(BLUEPRINT, CHARACTER))),
            merged.conflicts,
            "one broken id must produce one conflict no matter how many modules repeated it",
        )
        // Still resolvable, deterministically, so every reference to it stays validated.
        assertEquals(BLUEPRINT, merged.resolve("character/orc")?.kindFqn)
    }

    @Test
    fun `the empty catalog resolves nothing and suggests nothing`() {
        assertTrue(AssetCatalog.EMPTY.isEmpty)
        assertNull(AssetCatalog.EMPTY.resolve("character/orc"))
        assertEquals(emptyList(), AssetCatalog.EMPTY.nearest("charater/orc"))
        assertEquals(AssetCatalog.EMPTY, AssetCatalog.of(emptyList()))
        assertEquals(AssetCatalog.EMPTY, AssetCatalog.merge(emptyList()))
    }

    @Test
    fun `nearest returns character-slash-orc for the input charater-slash-orc`() {
        val catalog = AssetCatalog.of(listOf(orc, arrow))

        assertEquals(listOf("character/orc"), catalog.nearest("charater/orc"))
    }

    @Test
    fun `nearest ranks by edit distance and caps at the limit`() {
        val catalog = AssetCatalog.of(
            listOf(
                AssetCatalogEntry("character/orc", CHARACTER),
                AssetCatalogEntry("character/ork", CHARACTER),
                AssetCatalogEntry("character/orb", CHARACTER),
                AssetCatalogEntry("character/or", CHARACTER),
                AssetCatalogEntry("blueprint/arrow", BLUEPRINT),
            ),
        )

        val suggestions = catalog.nearest("character/orq")

        // All four `character/or*` ids are one edit away, so the cap and the tie-break are
        // both load-bearing: three of four, chosen by id order rather than insertion order.
        assertEquals(listOf("character/or", "character/orb", "character/orc"), suggestions)
        assertTrue("blueprint/arrow" !in suggestions, "an unrelated id is outside the edit budget")
    }

    @Test
    fun `nearest is case-insensitive because a wrong-case id is a typo`() {
        val catalog = AssetCatalog.of(listOf(orc))

        assertEquals(listOf("character/orc"), catalog.nearest("Character/Orc"))
    }

    @Test
    fun `a limit of zero is legal and a negative one is not`() {
        val catalog = AssetCatalog.of(listOf(orc))

        assertEquals(emptyList(), catalog.nearest("charater/orc", limit = 0))
        assertFailsWith<IllegalArgumentException> { catalog.nearest("charater/orc", limit = -1) }
    }

    @Test
    fun `prefix search returns everything under a folder, in sort order`() {
        val catalog = AssetCatalog.of(
            listOf(orc, arrow, AssetCatalogEntry("character/goblin", CHARACTER)),
        )

        assertEquals(
            listOf("character/goblin", "character/orc"),
            catalog.withPrefix("character/").map { it.id },
        )
        assertEquals(emptyList(), catalog.withPrefix("nothing/"))
    }

    @Test
    fun `a blank id or kind cannot be recorded`() {
        assertFailsWith<IllegalArgumentException> { AssetCatalogEntry(" ", CHARACTER) }
        assertFailsWith<IllegalArgumentException> { AssetCatalogEntry("character/orc", "") }
    }

    @Test
    fun `a conflict needs at least two kinds to be one`() {
        assertFailsWith<IllegalArgumentException> {
            AssetCatalogConflict("character/orc", listOf(CHARACTER))
        }
    }

    @Test
    fun `catalogs built from the same declarations in any order are equal`() {
        assertEquals(AssetCatalog.of(listOf(orc, arrow)), AssetCatalog.of(listOf(arrow, orc)))
        assertEquals(
            AssetCatalog.of(listOf(orc, arrow)).hashCode(),
            AssetCatalog.of(listOf(arrow, orc)).hashCode(),
        )
    }

    private companion object {
        const val CHARACTER = "dev.wildware.udea.assets.CharacterAsset"
        const val BLUEPRINT = "dev.wildware.udea.assets.BlueprintAsset"
    }
}
