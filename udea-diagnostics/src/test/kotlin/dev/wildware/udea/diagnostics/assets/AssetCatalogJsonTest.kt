package dev.wildware.udea.diagnostics.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssetCatalogJsonTest {

    private val catalog = AssetCatalog.of(
        listOf(
            AssetCatalogEntry("character/orc", "dev.wildware.udea.assets.CharacterAsset"),
            AssetCatalogEntry("blueprint/arrow", "dev.wildware.udea.assets.BlueprintAsset"),
        ),
    )

    @Test
    fun `the encoding is pinned, byte for byte`() {
        assertEquals(
            """
            {
              "version": 1,
              "assets": [
                {"id": "blueprint/arrow", "kind": "dev.wildware.udea.assets.BlueprintAsset"},
                {"id": "character/orc", "kind": "dev.wildware.udea.assets.CharacterAsset"}
              ]
            }

            """.trimIndent(),
            AssetCatalogJson.encode(catalog),
        )
    }

    /**
     * Issue #40's determinism criterion. Encoding twice is not the interesting half — encoding
     * two catalogs built from the *same declarations in a different order* is, because that is
     * what two Gradle runs over the same tree actually produce.
     */
    @Test
    fun `two runs from the same declarations produce identical bytes`() {
        val reordered = AssetCatalog.of(catalog.entries.reversed())

        assertEquals(AssetCatalogJson.encode(catalog), AssetCatalogJson.encode(reordered))
        assertTrue('\r' !in AssetCatalogJson.encode(catalog), "line endings must not be platform ones")
        assertTrue(
            AssetCatalogJson.encode(catalog).all { it.code in 0x20..0x7E || it == '\n' },
            "the document must be pure ASCII so its bytes do not depend on a default charset",
        )
    }

    @Test
    fun `an empty catalog round-trips`() {
        val encoded = AssetCatalogJson.encode(AssetCatalog.EMPTY)

        assertEquals("{\n  \"version\": 1,\n  \"assets\": []\n}\n", encoded)
        assertEquals(AssetCatalog.EMPTY, decoded(encoded))
    }

    @Test
    fun `encode then decode is the identity`() {
        assertEquals(catalog, decoded(AssetCatalogJson.encode(catalog)))
    }

    @Test
    fun `a non-ASCII id survives the escape round trip`() {
        val exotic = AssetCatalog.of(listOf(AssetCatalogEntry("character/örc\t\"x\"", "K")))
        val encoded = AssetCatalogJson.encode(exotic)

        assertTrue("\\u00f6" in encoded, "a non-ASCII character must be escaped: $encoded")
        assertEquals(exotic, decoded(encoded))
    }

    @Test
    fun `a bumped format version is reported, naming both versions`() {
        val decode = AssetCatalogJson.decode("""{"version": 99, "assets": []}""")

        val mismatch = assertIs<AssetCatalogDecode.VersionMismatch>(decode)
        assertEquals(99, mismatch.found)
        assertEquals(AssetCatalog.FORMAT_VERSION, mismatch.expected)
    }

    @Test
    fun `unknown keys are ignored and whitespace is irrelevant`() {
        val decode = AssetCatalogJson.decode(
            "{\"producer\":\"udea-assets-compiler\",\"version\":1,\"assets\":[\n" +
                "  {\"kind\":\"K\",\"id\":\"a\",\"extra\":[1,2,{\"z\":null}]}\n]}",
        )

        assertEquals(AssetCatalog.of(listOf(AssetCatalogEntry("a", "K"))), assertIs<AssetCatalogDecode.Ok>(decode).catalog)
    }

    @Test
    fun `malformed documents name the problem instead of throwing`() {
        for (bad in MALFORMED) {
            val decode = AssetCatalogJson.decode(bad)
            val malformed = assertIs<AssetCatalogDecode.Malformed>(decode, "expected malformed for: $bad")
            assertTrue(malformed.reason.isNotBlank(), "no reason given for: $bad")
        }
    }

    private fun decoded(text: String): AssetCatalog =
        assertIs<AssetCatalogDecode.Ok>(AssetCatalogJson.decode(text)).catalog

    private companion object {
        val MALFORMED = listOf(
            "",
            "   ",
            "[]",
            "{",
            "{\"version\": 1}",
            "{\"version\": \"1\", \"assets\": []}",
            "{\"assets\": []}",
            "{\"version\": 1, \"assets\": {}}",
            "{\"version\": 1, \"assets\": [1]}",
            "{\"version\": 1, \"assets\": [{\"kind\": \"K\"}]}",
            "{\"version\": 1, \"assets\": [{\"id\": \" \", \"kind\": \"K\"}]}",
            "{\"version\": 1, \"assets\": [{\"id\": \"a\", \"kind\": \"\"}]}",
            "{\"version\": 1, \"assets\": []} trailing",
            "{\"version\": 1, \"assets\": [{\"id\": \"a\", \"kind\": \"K\"}",
        )
    }
}
