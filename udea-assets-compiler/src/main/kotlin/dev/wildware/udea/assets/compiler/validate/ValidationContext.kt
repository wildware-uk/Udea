package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.compiler.AssetCompileResult
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.scan.ScanReport
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.diagnostics.DidYouMean
import dev.wildware.udea.diagnostics.SourceSpan
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/** One `reference("...")` in place: who holds it, in which field, and the reference itself. */
public data class RefSite(
    public val owner: DeclaredAsset,
    /** The declaration field the reference was passed to, e.g. `sheet` or `animations`. */
    public val field: String,
    public val ref: Ref,
)

/** One [ResFile] in place: who holds it, and in which field. */
public data class FileSite(
    public val owner: DeclaredAsset,
    public val field: String,
    public val path: ResFile,
)

/**
 * Everything pass 3 may read, and the derived views every validator would otherwise rebuild.
 *
 * Built from the **ordered declaration list** rather than from an [AssetGraph], because a graph
 * is keyed by id and a duplicate id is therefore invisible in one — `AssetGraph.of` says so
 * itself ("last writer wins on a duplicate id (pass 3 reports those)"). [graph] is derived here
 * so validators that only want resolution still get it.
 *
 * Nothing in this class reports a diagnostic and nothing in it throws on bad input: a validator
 * asks it questions and decides.
 */
public class ValidationContext(
    /** Every declaration in evaluation order, duplicates included. */
    public val declared: List<DeclaredAsset>,
    /** Absolute repository root; every emitted span is relative to it. */
    repoRoot: Path,
    /** Absolute asset root; every [ResFile] is resolved against it. */
    assetRoot: Path,
    /**
     * Pass 1's view of the same tree: one entry per declaration **as written**, with the span
     * of the declaring call.
     *
     * Not redundant with [declared], and the reason is a gap in pass 2 rather than a nicety.
     * `DeclaredAsset.origin` is filled only when `UdeaBuildContext.captureOrigins` is on, and
     * even then from a stack frame that knows a line and no column;
     * `AssetCompiler.withFallbackOrigins` fills in *reference* origins from pass 1 and leaves
     * declaration origins alone. So without this, every diagnostic anchored at a declaration -
     * a missing file, a bad grid, an out-of-range notify - is unlocated, which is most of pass 3.
     *
     * Empty is legal and degrades exactly as issue #85 says it must: the diagnostic keeps its
     * asset id and loses its line, and the build still fails for the right reason.
     */
    public val declarations: List<dev.wildware.udea.assets.compiler.scan.Declaration> = emptyList(),
    /**
     * The `.udea.kts` files the declarations came from.
     *
     * Only [DeterminismValidator] reads them, because it is the one check about the *source*
     * rather than about the graph. Defaulted to every script under the asset root so that a
     * caller cannot forget to supply them and quietly lose the check — the failure mode this
     * whole module exists to delete.
     */
    sources: List<Path>? = null,
) {
    /** Absolute, normalised. */
    public val repoRoot: Path = repoRoot.toAbsolutePath().normalize()

    /** Absolute, normalised. */
    public val assetRoot: Path = assetRoot.toAbsolutePath().normalize()

    /** Resolved by id, last declaration winning, exactly as pass 4 would see it. */
    public val graph: AssetGraph = AssetGraph.of(declared)

    /** Every declared id, sorted. The did-you-mean pool. */
    public val ids: List<String> = graph.assets.keys.sorted()

    public val sources: List<Path> = sources ?: runCatching { AssetCompiler.scriptsUnder(this.assetRoot) }
        .getOrDefault(emptyList())

    /** The declaration for [id], or null when nothing declares it. */
    public fun resolve(id: String): DeclaredAsset? = graph.assets[id]

    /** Every `reference("...")` any declaration holds, in declaration order. */
    public val refSites: List<RefSite> = declared.flatMap { asset ->
        asset.fields.flatMap { (field, value) ->
            collectRefs(value).map { RefSite(asset, field, it) }
        }
    }

    /** Every resource path any declaration holds, in declaration order. */
    public val fileSites: List<FileSite> = declared.flatMap { asset ->
        asset.fields.flatMap { (field, value) ->
            collectFiles(value).map { FileSite(asset, field, it) }
        }
    }

    /**
     * The closest declared id to [candidate], or null when nothing is close enough.
     *
     * Spec section 5 makes this mandatory rather than decorative: it is what lets an agent fix
     * a typo in the turn it is told about it instead of spending one listing the asset tree.
     * The distance policy is [DidYouMean]'s and is not restated here.
     */
    public fun didYouMean(candidate: String): String? = DidYouMean.suggest(candidate, ids)

    /** The closest declared id **of kind [kindFqn]** to [candidate]; see [didYouMean]. */
    public fun didYouMeanOfKind(candidate: String, kindFqn: String): String? = DidYouMean.suggest(
        candidate,
        graph.assets.values.filter { it.kindFqn == kindFqn }.map { it.id }.sorted(),
    )

    /** Where [path] would be on disk. */
    public fun fileOf(path: ResFile): Path = assetRoot.resolve(path.value)

    /**
     * Every non-script file under the asset root, repo-root-relative to the *asset* root and
     * `/`-separated: the pool [MissingFileValidator] suggests from.
     *
     * Walked once and lazily. An asset root is a few hundred files, and the alternative -
     * listing the parent directory of the missing path - answers nothing when the typo is in
     * the directory rather than in the file name, which is the case that costs an author most.
     */
    @OptIn(ExperimentalPathApi::class)
    public val resourceFiles: List<String> by lazy {
        runCatching {
            assetRoot.walk()
                .filter { it.isRegularFile() && !it.name.endsWith(UdeaDeclarationScanner.SCRIPT_SUFFIX) }
                .map { it.relativeTo(assetRoot).toString().replace('\\', '/') }
                .sorted()
                .toList()
        }.getOrDefault(emptyList())
    }

    /** Every pass-1 span for [id], in source order. More than one means a duplicate id. */
    public fun spansFor(id: String): List<SourceSpan> = spansById[id].orEmpty()

    private val spansById: Map<String, List<SourceSpan>> =
        declarations.groupBy({ it.id }, { it.span })

    /**
     * A span for a diagnostic about [asset]: [preferred] first, then what pass 2 captured, then
     * what pass 1 saw.
     *
     * All three may be null. Issue #85's rule is that a build never fails for want of a line
     * number, so a diagnostic with no span at all is legal and
     * [dev.wildware.udea.diagnostics.DiagnosticSink] falls back to the asset id when it dedupes
     * one.
     */
    public fun spanFor(asset: DeclaredAsset, preferred: SourceSpan? = null): SourceSpan? =
        preferred ?: asset.origin ?: spansById[asset.id]?.firstOrNull()

    /**
     * Two near-identical walks rather than one reified generic one, because an inline reified
     * helper cannot recurse into itself and a field value nests arbitrarily: a `Ref` lives
     * inside a `List` inside a `Map` in the DSL as it stands.
     */
    private fun collectRefs(value: Any?): List<Ref> = when (value) {
        is Ref -> listOf(value)
        is Iterable<*> -> value.flatMap(::collectRefs)
        is Map<*, *> -> value.values.flatMap(::collectRefs)
        else -> emptyList()
    }

    private fun collectFiles(value: Any?): List<ResFile> = when (value) {
        is ResFile -> listOf(value)
        is Iterable<*> -> value.flatMap(::collectFiles)
        is Map<*, *> -> value.values.flatMap(::collectFiles)
        else -> emptyList()
    }

    public companion object {
        /**
         * The context for a finished pass 2, with pass 1's scan for declaration spans.
         *
         * [scan] is nullable rather than absent so that a caller which genuinely has no scan can
         * say so, but a build always has one - pass 1 runs first and costs nothing to keep - and
         * omitting it silently costs every declaration-anchored diagnostic its line number.
         */
        public fun of(
            result: AssetCompileResult,
            repoRoot: Path,
            assetRoot: Path,
            scan: ScanReport? = null,
            sources: List<Path>? = null,
        ): ValidationContext = ValidationContext(
            declared = result.declared,
            repoRoot = repoRoot,
            assetRoot = assetRoot,
            declarations = scan?.declarations.orEmpty(),
            sources = sources,
        )
    }
}
