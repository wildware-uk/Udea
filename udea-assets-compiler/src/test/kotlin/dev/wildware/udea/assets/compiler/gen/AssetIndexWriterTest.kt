package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.pack.PackFixture
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.diagnostics.assets.AssetCatalog
import dev.wildware.udea.diagnostics.assets.AssetCatalogDecode
import dev.wildware.udea.diagnostics.assets.AssetCatalogJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `META-INF/udea/asset-index.json`, against `docs/contracts/asset-index.md`.
 *
 * The contract is frozen by the wave that landed the reader, so every assertion here is against
 * the document rather than against this writer's behaviour.
 */
class AssetIndexWriterTest {

    private fun declarations() =
        UdeaDeclarationScanner(TestPaths.repoRoot, PackFixture.assetRoot).use { it.scanTree() }.declarations

    private fun graph() = PackFixture.compile(TestPaths.repoRoot, PackFixture.assetRoot, "asset-index")

    @Test
    fun `the path is the one the contract names`() {
        assertEquals("META-INF/udea/asset-index.json", AssetIndexWriter.RESOURCE_PATH)
        assertEquals(AssetCatalog.RESOURCE_PATH, AssetIndexWriter.RESOURCE_PATH)
    }

    @Test
    fun `the document the reader gets back is the catalog that went in`() {
        val catalog = AssetIndexWriter.catalogOfScan(declarations())

        val decoded = AssetCatalogJson.decode(AssetIndexWriter.fromScan(declarations()))

        assertEquals(catalog, assertIs<AssetCatalogDecode.Ok>(decoded).catalog)
        assertTrue(catalog.entries.isNotEmpty())
    }

    @Test
    fun `every entry names a real AssetData type`() {
        val catalog = AssetIndexWriter.catalogOfScan(declarations())

        for (entry in catalog.entries) {
            val loaded = Class.forName(entry.kindFqn)
            assertTrue(
                dev.wildware.udea.assets.AssetData::class.java.isAssignableFrom(loaded),
                "${entry.kindFqn} (id '${entry.id}') is not an AssetData",
            )
        }
    }

    /**
     * A declaration with no runtime type is absent, not guessed.
     *
     * The contract is explicit that publishing `dev.wildware.udea.assets.Character` on the
     * strength of the word `character` would be *worse* than absence: an unresolvable `kindFqn`
     * is a silent case in the FIR checker, so the id would be indexed and unvalidated at once.
     */
    @Test
    fun `an unpublishable kind is absent from the index`() {
        val catalog = AssetIndexWriter.catalogOfScan(declarations())

        assertTrue(
            catalog.entries.none { it.id == "character/orc" },
            "`character/orc` has no runtime type and must not be indexed",
        )
        assertTrue(catalog.entries.any { it.id == "character/orc_idle" }, "its siblings are indexed")
    }

    /**
     * The two producers agree.
     *
     * The scan knows only the DSL word and the graph knows the `KClass`; they arrive at the same
     * document or one of them is wrong. Nothing else in the build compares them, so without this
     * the format would quietly acquire two dialects the moment the DSL grew a word.
     */
    @Test
    fun `the syntactic and the evaluated producer write the same document`() {
        assertEquals(AssetIndexWriter.fromScan(declarations()), AssetIndexWriter.fromGraph(graph()))
    }

    @Test
    fun `the document is byte-identical across two runs`() {
        assertEquals(AssetIndexWriter.fromScan(declarations()), AssetIndexWriter.fromScan(declarations()))
        assertEquals(AssetIndexWriter.fromGraph(graph()), AssetIndexWriter.fromGraph(graph()))
    }

    @Test
    fun `the document carries no timestamp, path or host name`() {
        val text = AssetIndexWriter.fromScan(declarations())

        assertTrue(TestPaths.repoRoot.toString() !in text, "the repo root leaked into the index")
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}""").find(text) == null,
            "something date-shaped is in the index: $text",
        )
        assertTrue(text.all { it.code in 0x20..0x7E || it == '\n' }, "the index must be pure ASCII")
        assertTrue(text.endsWith("\n") && !text.endsWith("\n\n"), "exactly one trailing newline")
        assertTrue("\r" !in text, "line endings must be \\n only")
    }

    /** The unpublishable kinds are reported rather than dropped on the floor. */
    @Test
    fun `the export lists what it could not publish`() {
        val export = AssetIndexWriter.exportOf(graph())

        assertEquals(listOf("character/orc"), export.unpublishable.map { it.id })
        assertEquals(listOf("character"), export.unpublishable.map { it.dslName })
    }
}
