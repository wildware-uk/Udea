package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.CatalogExport
import dev.wildware.udea.assets.compiler.scan.Declaration
import dev.wildware.udea.assets.compiler.toCatalog
import dev.wildware.udea.diagnostics.assets.AssetCatalog
import dev.wildware.udea.diagnostics.assets.AssetCatalogEntry
import dev.wildware.udea.diagnostics.assets.AssetCatalogJson

/**
 * Produces `META-INF/udea/asset-index.json`, the resource `udea-compiler-plugin`'s
 * `ClasspathAssetCatalogScanner` already reads.
 *
 * ## The format is not invented here
 *
 * `docs/contracts/asset-index.md` is the agreement, `AssetCatalog` in `udea-diagnostics` is the
 * type, and `AssetCatalogJson.encode` is the only encoder. This file writes nothing by hand.
 * Wave 1 landed the reader against that contract; a second spelling of the same document -
 * even one that differed only in key order or indent - would make the FIR checker silently see
 * an empty catalog and every `reference("...")` in `.kt` go unvalidated, which is a failure
 * with no symptom.
 *
 * ## Two producers, one document
 *
 * [fromScan] builds it from the **syntactic** pass-1 scan; [fromGraph] builds it from the
 * **evaluated** graph. They exist for different moments: the scan is available before anything
 * is compiled, which is what the in-editor checker needs, and the graph is available only after
 * evaluation but knows kinds the scan can only guess by DSL word.
 *
 * `AssetIndexAgreementTest` asserts the two produce the same document for the fixture corpus.
 * That is the check that matters: two producers of one contract that nobody compares is how a
 * format ends up with two dialects.
 */
public object AssetIndexWriter {

    /** The resource path, from the contract. Never spelled locally. */
    public const val RESOURCE_PATH: String = AssetCatalog.RESOURCE_PATH

    /** The catalog implied by a pass-1 declaration scan. */
    public fun catalogOfScan(declarations: List<Declaration>): AssetCatalog = AssetCatalog.of(
        declarations.mapNotNull { declaration ->
            DslKinds.fqnOf(declaration.kind)?.let { AssetCatalogEntry(declaration.id, it) }
        },
    )

    /** `META-INF/udea/asset-index.json` for a pass-1 scan. */
    public fun fromScan(declarations: List<Declaration>): String =
        AssetCatalogJson.encode(catalogOfScan(declarations))

    /** `META-INF/udea/asset-index.json` for an evaluated graph. */
    public fun fromGraph(graph: AssetGraph): String = AssetCatalogJson.encode(graph.toCatalog().catalog)

    /** The evaluated graph's export, so a caller can also report the unpublishable kinds. */
    public fun exportOf(graph: AssetGraph): CatalogExport = graph.toCatalog()
}
