package dev.wildware.udea.assets.compiler.pipeline

import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.atlas.AtlasPacker
import dev.wildware.udea.assets.compiler.atlas.SheetInput
import dev.wildware.udea.assets.compiler.pack.BundleContent
import dev.wildware.udea.assets.compiler.pack.BundleWriter
import dev.wildware.udea.assets.compiler.pack.GraphPacker
import dev.wildware.udea.assets.compiler.pack.PackedAtlas
import dev.wildware.udea.assets.compiler.scan.ScanReport
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.assets.compiler.validate.AssetValidatorPipeline
import dev.wildware.udea.assets.compiler.validate.ValidationContext
import dev.wildware.udea.diagnostics.DiagnosticReport
import dev.wildware.udea.diagnostics.DiagnosticSink
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * The five passes of spec 3.6, as plain functions over paths.
 *
 * ## Why this exists next to [dev.wildware.udea.assets.compiler.daemon.AssetDaemon]
 *
 * The daemon is these passes plus *state* - a last-good graph, a per-file map, a diff between
 * two graphs. A Gradle build has none of that and wants the passes once, over a whole tree,
 * writing files. Before this, the only whole-tree driver was
 * [dev.wildware.udea.assets.compiler.pack.AssetPackCli], which said in its own KDoc that it was
 * "not the shipped asset pipeline". This is.
 *
 * There is deliberately **no Gradle type here** (`UDEA-MG-003`), and there is deliberately no
 * second implementation of any pass: every function below is a call into
 * [UdeaDeclarationScanner], [AssetCompiler], [AssetValidatorPipeline], [GraphPacker],
 * [AtlasPacker] and [BundleWriter], in that order. That is the whole point - the daemon an agent
 * validates against and the Gradle task CI runs drive the same six objects, so they cannot
 * disagree about whether an asset tree is valid.
 */
public object AssetPipeline {

    /** Pass 1: the PSI scan, with no classpath and nothing compiled. */
    public fun scan(repoRoot: Path, assetRoot: Path): ScanReport =
        UdeaDeclarationScanner(repoRoot, assetRoot).use { it.scanTree() }

    /** What one [compileAndValidate] produced. */
    public data class Compiled(
        public val graph: AssetGraph,
        public val declared: List<DeclaredAsset>,
        public val report: DiagnosticReport,
    ) {
        public val hasErrors: Boolean get() = report.diagnostics.any { it.severity == Severity.Error }
    }

    /**
     * Passes 2 and 3: evaluate every script, then run the whole validator suite over the result.
     *
     * The compiler's own diagnostics and the validators' go into one [DiagnosticReport] through
     * `AssetValidatorPipeline`, which is the only thing in the repository that ranks, collapses
     * and caps - so a Gradle build's `diagnostics.json` is ordered by the same rules an agent's
     * `assets.validate` answer is.
     */
    public fun compileAndValidate(
        repoRoot: Path,
        assetRoot: Path,
        scriptClasspath: List<Path>,
        cacheDirectory: Path,
        scan: ScanReport = scan(repoRoot, assetRoot),
    ): Compiled {
        val sources = AssetCompiler.scriptsUnder(assetRoot)
        val compiler = AssetCompiler(repoRoot, assetRoot, scriptClasspath.filter { it.toFile().exists() }, cacheDirectory)
        val result = compiler.compile(sources, scan.referenceSpanIndex())
        val context = ValidationContext.of(result, repoRoot, assetRoot, scan, sources)
        val validated = AssetValidatorPipeline().validate(context)
        // The scan's own diagnostics first: a file pass 1 could not parse is the root cause of
        // whatever pass 2 then failed to compile in it, and losing it to the cap would leave the
        // author with the consequence and not the defect.
        val sink = DiagnosticSink()
        sink.reportAll(scan.diagnostics)
        sink.reportAll(result.diagnostics)
        sink.reportAll(validated.diagnostics)
        val report = sink.build()
        return Compiled(
            result.graph,
            result.declared,
            // The validators' own suppression count is carried through rather than recomputed:
            // this sink never saw the diagnostics that one collapsed, so adding is the only way
            // `suppressedCount` stays the number of defects a reader is not being shown.
            report.copy(suppressedCount = report.suppressedCount + validated.suppressedCount),
        )
    }

    /**
     * Pass 4: the graph records, the atlas, and the `.udeapak` bytes.
     *
     * The sheets packed are the ones the **graph declares** - id, path, rows and columns straight
     * off each `spriteSheet(...)` - and not every PNG under a directory. That is the difference
     * between an atlas whose region names are asset ids the runtime can look up, and an atlas
     * keyed by file path that a renderer has to guess its way into. It also means a sheet nobody
     * declared costs nothing, and a declared sheet whose file is missing is a
     * `MissingFileValidator` diagnostic rather than a silently absent region.
     */
    public fun pack(assetRoot: Path, graph: AssetGraph): PackResult {
        val packed = GraphPacker.pack(graph)
        val sheets = sheetsOf(assetRoot, graph)
        val atlas = if (sheets.isEmpty()) PackedAtlas.EMPTY else AtlasPacker().pack(sheets)
        val bytes = BundleWriter.write(BundleContent.reachable(assets = packed.assets, atlas = atlas))
        return PackResult(bytes, packed.assets.size, sheets.size, atlas.pages.size, packed.diagnostics)
    }

    /** The bundle, and enough about it to log a line that means something. */
    public data class PackResult(
        public val bytes: ByteArray,
        public val assets: Int,
        public val sheets: Int,
        public val pages: Int,
        public val diagnostics: List<UdeaDiagnostic>,
    ) {
        public val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.Error }

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Every declared `spriteSheet` whose file is on disk, as a packer input, sorted by id.
     *
     * Sorted here as well as inside [AtlasPacker] so that a caller comparing two runs is
     * comparing the packer's determinism rather than `Files.walk`'s.
     */
    public fun sheetsOf(assetRoot: Path, graph: AssetGraph): List<SheetInput> =
        graph.assets.values
            .filter { it.kind == SHEET_KIND }
            .mapNotNull { asset ->
                val path = asset.fields[SHEET_PATH_FIELD] as? ResFile ?: return@mapNotNull null
                val file = assetRoot.resolve(path.value)
                if (!file.isRegularFile()) return@mapNotNull null
                SheetInput(
                    id = asset.id,
                    file = file,
                    columns = asset.fields["columns"] as? Int ?: 1,
                    rows = asset.fields["rows"] as? Int ?: 1,
                )
            }
            .sortedBy { it.id }

    /** The DSL word whose declarations become atlas pages. */
    public const val SHEET_KIND: String = "spriteSheet"

    /** The field on a `spriteSheet(...)` that names its image. */
    public const val SHEET_PATH_FIELD: String = "spritePath"
}
