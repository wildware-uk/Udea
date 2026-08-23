package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.ChangedAsset
import dev.wildware.udea.assets.GraphDelta
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.pack.PackedValues
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.assets.compiler.validate.UnresolvedReferenceValidator
import dev.wildware.udea.assets.compiler.validate.ValidationContext
import dev.wildware.udea.diagnostics.DiagnosticSink
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * What one validate produced. Non-mutating: a validate never becomes the daemon's last-good graph.
 */
public data class ValidationReport(
    public val diagnostics: List<UdeaDiagnostic>,
    public val durationMs: Long,
    /** Scripts recompiled rather than answered from the warm jar cache. The latency story. */
    public val recompiled: Int,
) {
    /** No errors. Warnings do not make a validate red - spec 5 keeps severity meaningful. */
    public val ok: Boolean get() = diagnostics.none { it.severity == Severity.Error }
}

/**
 * The compiler, kept warm, holding the last graph that validated.
 *
 * ## Why this is not a second implementation
 *
 * Every pass this drives is the one a Gradle task would drive: [AssetCompiler] for pass 2,
 * [UdeaDeclarationScanner] for pass 1, [UnresolvedReferenceValidator] for pass 3, and
 * [PackedValues] - the real bundle writer and the real reader - for the values a reload swaps in. This class adds *state* - a per-file graph, a last-good graph,
 * and the diff between two of them - and no compilation logic at all. That is what `UDEA-MG-003`
 * (no Gradle types in this module) is protecting: if an agent validates green here and CI then
 * fails, trust in the fast loop is gone, and the only durable way to prevent that is that there
 * is nothing here for CI to disagree with.
 *
 * ## Incremental
 *
 * [reload] recompiles **only the files it is told changed** and merges their assets over the
 * per-file lists it already holds. That is the whole reason the warm path is fast: compiling one
 * script is a fraction of the cost of compiling nineteen even against a hot jar cache.
 *
 * ## Last-good
 *
 * [ids], [declaration] and [value] only ever move forward through a reload that validated, packed
 * **and was committed**. A recompile with an error, an unpackable kind, or a shape change leaves
 * every field of this class exactly as the previous successful reload left it.
 *
 * ## Threading
 *
 * Single-threaded, and says so rather than taking a lock that would be sometimes-enough. A host
 * serialises the watcher thread and the MCP handler thread onto one executor.
 */
public class AssetDaemon(
    private val repoRoot: Path,
    private val assetRoot: Path,
    scriptClasspath: List<Path>,
    cacheDirectory: Path,
) {

    /**
     * The compile classpath, with entries that do not exist on disk removed.
     *
     * A Gradle runtime classpath routinely names directories the build never created -
     * `build/classes/java/main` in a module with no Java sources is the usual one - and the Kotlin
     * script compiler reports each as a `UDEA0021` warning **attributed to the script it was
     * compiling**, at line 0. Measured on the Phase 2 demo, one typo'd reference came back as one
     * useful error and *eight* of those, taking the answer from roughly 700 characters to 3409 and
     * burying the diagnostic an agent actually asked for.
     *
     * Filtered here rather than in every caller. `PackFixture` had already learned this and
     * filtered its own; a daemon that made each host learn it separately would keep producing the
     * noise for whichever host forgot. This is not hiding a real diagnostic: a classpath entry
     * that does not exist contributes no classes, so removing it cannot change what compiles.
     */
    private val resolvedClasspath: List<Path> = scriptClasspath.filter { it.exists() }

    private val compiler = AssetCompiler(repoRoot, assetRoot, resolvedClasspath, cacheDirectory)

    private val scanner = UdeaDeclarationScanner(repoRoot, assetRoot)

    /** Per-file pass-2 output, so a reload recompiles one file and re-merges rather than all. */
    private val byFile = LinkedHashMap<Path, List<DeclaredAsset>>()

    /** The last graph that validated, packed and was committed. Empty until [start]. */
    private var lastGood = LinkedHashMap<String, DeclaredAsset>()

    /** The packed values behind [lastGood]. What a reload diffs against. */
    private var packedValues = LinkedHashMap<AssetId, AssetData>()

    /** The graph a decided reload would install, held until the caller has pushed its delta. */
    private var pending: Map<Path, List<DeclaredAsset>>? = null

    /** How many reloads have been committed. Lets a tool result be attributed to one. */
    public var generation: Int = 0
        private set

    /** Every id in the last-good graph, sorted. */
    public val ids: List<String> get() = lastGood.keys.sorted()

    /** The declaration behind [id] in the last-good graph, or `null`. */
    public fun declaration(id: String): DeclaredAsset? = lastGood[id]

    /** The packed runtime value behind [id], or `null` when its kind is not packable yet. */
    public fun value(id: String): AssetData? = packedValues[AssetId(id)]

    /**
     * Every `.udea.kts` under the asset root, sorted, absolute and normalised.
     *
     * Normalised at the one place paths enter, not at each comparison: [byFile] is keyed by path
     * and [reload] is told which paths changed by a watcher that has its own idea of how to spell
     * them. Two spellings of one file put the same script in the map twice, and the graph then
     * holds both the old declaration and the new one - the two-keys-for-one-file bug `ResPath`
     * exists to kill, reproduced one level up.
     */
    public fun scripts(): List<Path> =
        AssetCompiler.scriptsUnder(assetRoot).map { it.toAbsolutePath().normalize() }

    /** The file the last-good graph attributes [id] to, or `null`. */
    public fun fileOf(id: String): Path? =
        byFile.entries.firstOrNull { entry -> entry.value.any { it.id == id } }?.key

    /**
     * Compiles the whole tree and takes the result as the last-good graph.
     *
     * Returns the report rather than throwing on a broken tree. A daemon told to start against a
     * corpus with a typo in it should come up, serve `assets_validate`, and let the agent fix the
     * typo; refusing to start is how a dev loop becomes a restart loop.
     */
    public fun start(): ValidationReport {
        val began = System.nanoTime()
        byFile.clear()
        val files = scripts()
        val diagnostics = files.flatMap { compileInto(byFile, it) }

        val graph = AssetGraph.of(byFile.values.flatten())
        val source = diagnostics + references(graph)
        // Packed only when the source is clean. The bundle writer refuses a graph whose
        // references do not resolve, so packing a broken corpus reports the same defect a second
        // time in a less useful shape.
        val packed = if (source.any { it.severity == Severity.Error }) null else PackedValues.of(graph)
        val ranked = rank(source + packed?.diagnostics.orEmpty())
        if (packed != null && ranked.none { it.severity == Severity.Error }) {
            lastGood = LinkedHashMap(graph.assets)
            packedValues = LinkedHashMap(packed.values)
        }
        return ValidationReport(ranked, millisSince(began), files.size)
    }

    /**
     * Recompiles [changed] and reports what it would do to a running graph.
     *
     * Every path except [ReloadOutcome.Applied] leaves this daemon exactly as it found it. An
     * `Applied` outcome parks the new graph and waits for [commit] - see that method for why the
     * daemon must not move its own graph at decision time.
     */
    public fun reload(changed: Collection<Path>): ReloadOutcome {
        val began = System.nanoTime()
        val touched = changed.map { it.toAbsolutePath().normalize() }.distinct()
        if (touched.isEmpty()) return ReloadOutcome.NoChange(millisSince(began))

        val live = touched.filter { it.isScript() }

        // The candidate graph: everything the daemon holds, with the touched files' contribution
        // replaced by what they declare now. A deleted file contributes nothing, which is how a
        // deletion becomes an `asset_removed` shape change instead of a stale entry nobody notices.
        val candidateByFile = LinkedHashMap(byFile)
        touched.forEach { candidateByFile.remove(it) }
        val compileDiagnostics = live.flatMap { compileInto(candidateByFile, it) }

        val candidate = AssetGraph.of(candidateByFile.values.flatten())
        val diagnostics = compileDiagnostics + references(candidate)
        if (diagnostics.any { it.severity == Severity.Error }) {
            return ReloadOutcome.Rejected(rank(diagnostics), millisSince(began))
        }

        val packed = PackedValues.of(candidate)
        if (packed.diagnostics.any { it.severity == Severity.Error }) {
            return ReloadOutcome.Rejected(rank(packed.diagnostics), millisSince(began))
        }

        val structural = structuralChanges(candidate, packed.values)
        if (structural.isNotEmpty()) return ReloadOutcome.RequiresRestart(structural, millisSince(began))

        val changedAssets = mutableListOf<ChangedAsset>()
        for ((id, declared) in candidate.assets) {
            if (lastGood[id] == declared) continue
            val assetId = AssetId(id)
            // Every id in the candidate graph has a value here, because a graph that could not
            // be packed was rejected above. The elvis is the honest response to a branch that
            // cannot be reached rather than a swallowed failure.
            val value = packed.values[assetId] ?: continue
            if (value != packedValues[assetId]) changedAssets += ChangedAsset(assetId, value)
        }
        if (changedAssets.isEmpty()) return ReloadOutcome.NoChange(millisSince(began))

        pending = candidateByFile
        return ReloadOutcome.Applied(GraphDelta(changedAssets), millisSince(began))
    }

    /**
     * Takes the pending graph as last-good, after the caller has applied the delta to its registry.
     *
     * Separate from [reload] because the two happen on different threads at different times: the
     * daemon decides, and the delta lands at the top of a `Simulation.step` some milliseconds
     * later. A daemon that moved its own graph at decision time would answer `assets_get` with a
     * value the running game has not been given yet, and an agent comparing the two would be
     * reasoning about a graph that exists nowhere.
     */
    public fun commit() {
        val installed = pending ?: return
        byFile.clear()
        byFile.putAll(installed)
        val graph = AssetGraph.of(byFile.values.flatten())
        lastGood = LinkedHashMap(graph.assets)
        packedValues = LinkedHashMap(PackedValues.of(graph).values)
        pending = null
        generation++
    }

    /** Discards a decided-but-unpushed reload. What a caller whose push failed calls. */
    public fun rollback() {
        pending = null
    }

    /**
     * Recompiles [files] (or every file the daemon holds) and reports, touching nothing.
     *
     * The agent's compile loop. Warm and scoped to one file this is one script compile, one
     * syntactic scan and one graph walk, which is what keeps it inside the 300ms spec 6 gates.
     *
     * The recompiled files are overlaid on the last-good graph rather than validated alone: a file
     * validated in isolation reports every reference into its neighbours as unresolved, which is
     * the most direct way to build a fast validator nobody trusts.
     */
    public fun validate(files: Collection<Path> = emptyList()): ValidationReport {
        val began = System.nanoTime()
        val targets = (if (files.isEmpty()) byFile.keys.toList() else files.map { it.toAbsolutePath().normalize() })
            .filter { it.isScript() }
        if (targets.isEmpty()) return ValidationReport(emptyList(), millisSince(began), 0)

        val overlay = LinkedHashMap(byFile)
        targets.forEach { overlay.remove(it) }
        val compileDiagnostics = targets.flatMap { compileInto(overlay, it) }

        val graph = AssetGraph.of(overlay.values.flatten())
        val diagnostics = compileDiagnostics + references(graph)
        return ValidationReport(rank(diagnostics), millisSince(began), targets.size)
    }

    // --- internals ------------------------------------------------------------------------------

    /**
     * Compiles one script and files what it declared under that script in [target].
     *
     * **One file per [AssetCompiler.compile] call**, and that is the whole of the attribution
     * story. The obvious alternative - compile the corpus in one call and file each asset by the
     * span pass 2 stamped on it - is wrong, and wrong silently: a `DeclaredAsset`'s own origin is
     * only populated when `UdeaBuildContext.captureOrigins` is on, so with it off *every* asset
     * fell back to "the first file compiled". A deleted script then removed a key holding nothing,
     * and an edited one removed a key holding the whole graph - the first reported no change, the
     * second reported every other asset as deleted. Compiling per file removes the guess rather
     * than making it better, and the warm jar cache means it costs a map lookup per unchanged file.
     */
    private fun compileInto(target: MutableMap<Path, List<DeclaredAsset>>, file: Path): List<UdeaDiagnostic> {
        val scan = scanner.scanFiles(listOf(file))
        val result = compiler.compile(listOf(file), scan.referenceSpanIndex())
        target[file] = result.graph.assets.values.toList()
        return result.diagnostics
    }

    /**
     * Shape differences between the last-good graph and [candidate], as spec 3.6 defines them.
     *
     * Three of the four are visible in ids and kinds alone. The fourth - a blueprint whose
     * component list moved - needs the packed value, and is decided here rather than left to
     * `AssetRegistry.classify`, which by design compares only the runtime *class* of a value and
     * would call it an ordinary value change.
     */
    private fun structuralChanges(
        candidate: AssetGraph,
        candidateValues: Map<AssetId, AssetData>,
    ): List<StructuralChange> {
        val changes = mutableListOf<StructuralChange>()
        for (id in candidate.ids - lastGood.keys) changes += StructuralChange.added(AssetId(id))
        for (id in lastGood.keys - candidate.ids) changes += StructuralChange.removed(AssetId(id))
        for (id in candidate.ids intersect lastGood.keys) {
            val before = lastGood.getValue(id)
            val after = candidate.assets.getValue(id)
            if (before.kind != after.kind) {
                changes += StructuralChange.kindChanged(AssetId(id), before.kind, after.kind)
                continue
            }
            if (after.kind != "blueprint") continue
            val old = (packedValues[AssetId(id)] as? Blueprint)?.components?.map { it.type.value } ?: continue
            val new = (candidateValues[AssetId(id)] as? Blueprint)?.components?.map { it.type.value } ?: continue
            if (old != new) changes += StructuralChange.blueprintComponents(AssetId(id), old, new)
        }
        return changes.sortedBy { it.id.value }
    }

    /**
     * Does every reference in [graph] name something the graph declares?
     *
     * One line, into `validate/UnresolvedReferenceValidator`, and that is the whole point. There
     * used to be a `daemon/AssetGraphValidator` here doing the same walk with its own grouping
     * and its own did-you-mean threshold - a second answer to "is this reference valid", which
     * is exactly how a daemon comes to say yes to something CI says no to.
     *
     * `sources = emptyList()` because the only validator that reads them is `DeterminismValidator`
     * and this is not running it; the default would walk the whole asset tree on every
     * keystroke-driven validate, inside a 300ms budget, to build a list nothing here looks at.
     */
    private fun references(graph: AssetGraph): List<UdeaDiagnostic> =
        UnresolvedReferenceValidator.validate(
            ValidationContext(
                declared = graph.assets.values.toList(),
                repoRoot = repoRoot,
                assetRoot = assetRoot,
                sources = emptyList(),
            ),
        )

    /** The repo-relative path a span for this file carries. */
    private fun Path.spanPath(): String = SourceSpan.relativize(
        repoRoot.toAbsolutePath().normalize().toString(),
        toAbsolutePath().normalize().toString(),
    )

    private fun Path.isScript(): Boolean =
        toFile().isFile && toString().endsWith(UdeaDeclarationScanner.SCRIPT_SUFFIX)

    private fun millisSince(nanos: Long): Long = (System.nanoTime() - nanos) / 1_000_000

    public companion object {

        /**
         * The most diagnostics a tool result carries (spec 5).
         *
         * A cap, not a budget: past twenty-five an agent is reading noise, and the twenty-sixth is
         * almost always a consequence of the first.
         */
        public const val MAX_DIAGNOSTICS: Int = 25

        /**
         * Deduped, root-cause-collapsed, ranked and capped - by [DiagnosticSink], not here.
         *
         * This used to be a hand-rolled sort with a `take(25)` on the end, defensible only
         * because `daemon/AssetGraphValidator` had already collapsed five referrers of one
         * missing id into a single diagnostic itself. That collapse now happens where every
         * other producer's does, so the daemon's answer and `udeaValidateAssets`'s
         * `diagnostics.json` are ordered by one implementation rather than by two that agreed
         * with each other by coincidence.
         */
        public fun rank(diagnostics: List<UdeaDiagnostic>): List<UdeaDiagnostic> =
            DiagnosticSink(MAX_DIAGNOSTICS).apply { reportAll(diagnostics) }.build().diagnostics
    }
}
