package dev.wildware.udea.diagnostics.assets

import dev.wildware.udea.diagnostics.DidYouMean

/**
 * One declared asset, as the compile-time catalog records it.
 *
 * @param id the reference string an author writes inside `reference("...")`.
 * @param kindFqn fully qualified name of the asset's declared type, so a checker can tell
 *   `reference<SpriteAnimation>("character/orc")` from `reference<Blueprint>("character/orc")`
 *   without loading the asset.
 */
public data class AssetCatalogEntry(
    public val id: String,
    public val kindFqn: String,
) : Comparable<AssetCatalogEntry> {
    init {
        require(id.isNotBlank()) { "asset id must not be blank" }
        require(kindFqn.isNotBlank()) { "asset kind must not be blank (id '$id')" }
    }

    /** Total order by `(id, kindFqn)`: the serialised order, and the merge order. */
    override fun compareTo(other: AssetCatalogEntry): Int =
        compareValuesBy(this, other, { it.id }, { it.kindFqn })
}

/**
 * One asset id that more than one module declared, with more than one kind.
 *
 * Held as a list of kinds rather than a list of occurrences on purpose: spec section 5 caps
 * diagnostics and ranks root cause first, so one broken id must produce one diagnostic no
 * matter how many modules repeated it.
 */
public data class AssetCatalogConflict(
    public val id: String,
    /** Every distinct kind declared for [id], sorted. At least two, or it is not a conflict. */
    public val kinds: List<String>,
) {
    init {
        require(kinds.size >= 2) { "'$id' is not a conflict: it has ${kinds.size} kind(s)" }
    }
}

/**
 * The **build-time** asset catalog: what ids exist, and what type each one is.
 *
 * ### This is not `AssetIndex`
 *
 * Spec section 3.6 also has an `AssetIndex`: a pack-time-stable *integer* that is the only
 * asset identity allowed to enter a snapshot. That is a runtime concept owned by
 * `udea-assets`. This is a build-time concept: a list of strings on a compile classpath that
 * exists so `reference("charater/orc")` can be red in an editor. The two were flagged in
 * review for sharing a word; they deliberately do not share a type name.
 *
 * ### Why it lives in `udea-diagnostics`
 *
 * Issue #40 asks for "a zero-dependency leaf module that both `udea-compiler-plugin` and
 * `udea-assets-compiler` can depend on without either pulling the other in". That module
 * already exists and this is it — see `ModuleGraphTest`, which holds this module to the Kotlin
 * stdlib and nothing else, and it is already on the compile classpath of the K2 plugin, the
 * KSP processor and the asset compiler at once. A fourth leaf would have meant editing
 * `settings.gradle.kts`, `ModuleGraphRules.HEADLESS_PROJECTS` and
 * `UdeaCompilerPluginWiring.EXCLUSIONS` (asserted to be exactly the plugin plus its own
 * project dependencies) to obtain a property this module already has.
 *
 * ### Shape
 *
 * [entries] is sorted and deduplicated, so two catalogs built from the same declarations are
 * equal and their serialised bytes are identical. An id declared twice with the *same* kind is
 * one entry; declared twice with *different* kinds it keeps both entries — [resolve] answers
 * with the first in sort order so resolution stays deterministic — and appears exactly once in
 * [conflicts].
 */
public class AssetCatalog private constructor(
    public val entries: List<AssetCatalogEntry>,
) {

    /**
     * Ids declared with more than one kind, one element per id, in id order.
     *
     * A reader surfaces these; this class does not decide whether they are an error, because
     * the same catalog is read by a checker that must degrade to silence and by a validator
     * that must not.
     */
    public val conflicts: List<AssetCatalogConflict> = entries
        .groupBy { it.id }
        .mapNotNull { (id, group) ->
            val kinds = group.map { it.kindFqn }.distinct().sorted()
            if (kinds.size >= 2) AssetCatalogConflict(id, kinds) else null
        }
        .sortedBy { it.id }

    /**
     * First entry per id, in sort order.
     *
     * Built by folding rather than by `associateBy`, which keeps the *last* value for a
     * duplicate key: with a conflicting id that would make resolution depend on the kind that
     * sorts highest, for no reason anyone could state.
     */
    private val byId: Map<String, AssetCatalogEntry> =
        entries.fold(LinkedHashMap<String, AssetCatalogEntry>(entries.size)) { map, entry ->
            map.putIfAbsent(entry.id, entry)
            map
        }

    /** Every distinct id, sorted. The candidate pool for [nearest]. */
    public val ids: List<String> = entries.map { it.id }.distinct()

    /** True when nothing is indexed. The silent case: see the reader's contract. */
    public val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * The entry for [id], or `null` when nothing declares it.
     *
     * When [id] is in [conflicts] this answers with the lowest kind in sort order rather than
     * with `null`: a checker that went silent on a conflicting id would turn one duplicate
     * declaration into *no* validation of every reference to it.
     */
    public fun resolve(id: String): AssetCatalogEntry? = byId[id]

    /** Every entry whose id starts with [prefix], in sort order. */
    public fun withPrefix(prefix: String): List<AssetCatalogEntry> =
        entries.filter { it.id.startsWith(prefix) }

    /**
     * Up to [limit] ids closest to [candidate] by Levenshtein distance, nearest first.
     *
     * Spec section 5 calls the did-you-mean mandatory: it is what lets an agent correct a typo
     * in the same turn instead of spending one listing the asset tree. Distance is measured
     * case-insensitively, because a wrong-case id is exactly the typo this exists to catch.
     * Ties break by id, so the suggestion list never depends on iteration order.
     */
    public fun nearest(candidate: String, limit: Int = MAX_SUGGESTIONS): List<String> {
        require(limit >= 0) { "limit must not be negative, was $limit" }
        if (limit == 0 || ids.isEmpty()) return emptyList()
        val budget = DidYouMean.defaultMaxDistance(candidate)
        val needle = candidate.lowercase()
        return ids.asSequence()
            .map { it to DidYouMean.distance(needle, it.lowercase()) }
            .filter { (_, distance) -> distance <= budget }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is AssetCatalog && entries == other.entries)

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "AssetCatalog(${entries.size} entries)"

    public companion object {
        /**
         * Where a producer writes the catalog, and where the reader looks on every classpath
         * root. The resource keeps the name `asset-index.json` because issue #90's acceptance
         * criteria and `docs/contracts/asset-index.md` already name that file; it is the *type*
         * that had to stop being called `AssetIndex`.
         */
        public const val RESOURCE_PATH: String = "META-INF/udea/asset-index.json"

        /** Bumped only on an incompatible shape change. A reader refuses what it does not know. */
        public const val FORMAT_VERSION: Int = 1

        /** How many did-you-mean candidates a diagnostic may carry (issue #41). */
        public const val MAX_SUGGESTIONS: Int = 3

        /** The catalog a compilation with no index anywhere on its classpath sees. */
        public val EMPTY: AssetCatalog = AssetCatalog(emptyList())

        /** A catalog over [entries], sorted and deduplicated. */
        public fun of(entries: Iterable<AssetCatalogEntry>): AssetCatalog {
            val sorted = entries.distinct().sorted()
            return if (sorted.isEmpty()) EMPTY else AssetCatalog(sorted)
        }

        /**
         * The union of [catalogs] — how an upstream module's index reaches a downstream
         * compilation, since every module contributes its own resource to the classpath.
         */
        public fun merge(catalogs: Iterable<AssetCatalog>): AssetCatalog =
            of(catalogs.flatMap { it.entries })
    }
}
