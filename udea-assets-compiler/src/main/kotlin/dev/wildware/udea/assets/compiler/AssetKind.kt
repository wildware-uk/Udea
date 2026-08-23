package dev.wildware.udea.assets.compiler

import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.diagnostics.assets.AssetCatalog
import dev.wildware.udea.diagnostics.assets.AssetCatalogEntry
import kotlin.reflect.KClass

/**
 * The kind of one declaration, in the **one** vocabulary the whole Phase 2 pipeline agrees on.
 *
 * ## The seam this closes
 *
 * Three parties have to agree about what "kind" means, and until this type existed they did not:
 *
 * | Party | What it called a kind |
 * |---|---|
 * | `udea-assets` (the runtime model) | a Kotlin type — `SpriteSheet`, `Blueprint`, ... |
 * | `udea-assets-compiler` (the producer) | the **DSL function name** — `spriteSheet`, `blueprint` |
 * | `udea-compiler-plugin` (the consumer) | `AssetCatalogEntry.kindFqn`, a fully qualified name |
 *
 * The consumer's is the one that has to win, and not by seniority: it is the only one that can
 * answer the question the checker asks. `reference<SpriteAnimation>("character/orc")` is wrong
 * when the id resolves to something that is not a `SpriteAnimation`, and deciding that needs a
 * name a `ClassId` can be built from. `"spriteAnimation"` is not such a name, and no amount of
 * casing convention makes it one: [AssetData] is deliberately **not** sealed - "a game declares
 * its own kinds" - so a table mapping DSL words to engine types would be wrong in principle as
 * well as in fact, since the game's own kinds are not in any table this module could hold.
 *
 * So a declaration function states its kind by handing over the [KClass] it produces, and the
 * name is read off that. There is no string to keep in step, which is the only reason it will
 * stay in step.
 *
 * ## `null` is a real answer
 *
 * [Unpublishable] exists because the provisional DSL in [AssetScope] declares kinds the runtime
 * model has no type for - `character` is the live example - and the honest treatment of one is
 * a named diagnostic rather than an invented FQN. An entry in a compile classpath's catalog is
 * a promise that a class of that name exists for the checker to resolve; writing
 * `dev.wildware.udea.assets.Character` because the DSL function is called `character` would
 * make the checker go quiet on every reference to it (an unresolvable kind is a silent case by
 * contract), which is worse than the declaration being absent, because it is absent *and*
 * reported as present.
 */
public sealed interface AssetKind {

    /** The value written into [AssetCatalogEntry.kindFqn], or `null` when there is none. */
    public val fqn: String?

    /** A kind backed by a real [AssetData] implementation on this compilation's classpath. */
    public data class Declared(public val type: KClass<out AssetData>) : AssetKind {
        override val fqn: String = requireNotNull(type.qualifiedName) {
            "an asset kind must be a named class; ${type} is anonymous or local"
        }
    }

    /**
     * A DSL word with no runtime type behind it yet.
     *
     * @param dslName the declaration function, e.g. `character`.
     */
    public data class Unpublishable(public val dslName: String) : AssetKind {
        override val fqn: String? get() = null
    }

    public companion object {
        /** The kind of the [AssetData] subtype [T]. */
        public inline fun <reified T : AssetData> of(): AssetKind = Declared(T::class)
    }
}

/** The entry this asset contributes to the compile-time catalog, or `null` when it has no kind. */
public fun DeclaredAsset.catalogEntry(): AssetCatalogEntry? =
    kindFqn?.let { AssetCatalogEntry(id = id, kindFqn = it) }

/**
 * The compile-time catalog for this graph, plus what could not go into it.
 *
 * The bytes are **not** produced here: [AssetCatalog] and its encoder live in `udea-diagnostics`,
 * the leaf both the producer and the K2 checker already depend on, and pass 5 (issue #90) writes
 * `AssetCatalogJson.encode(catalog)` to [AssetCatalog.RESOURCE_PATH]. That split is the point -
 * one encoder, so "byte-identical across two runs" is a property of the format rather than of
 * whoever happened to write the file.
 */
public data class CatalogExport(
    public val catalog: AssetCatalog,
    /** Ids whose kind has no [AssetData] type, in id order. Never silently dropped. */
    public val unpublishable: List<UnpublishableAsset>,
)

/** One declaration that cannot be published to the catalog, and why. */
public data class UnpublishableAsset(public val id: String, public val dslName: String)

/** Builds the compile-time catalog, reporting rather than guessing at a missing kind. */
public fun AssetGraph.toCatalog(): CatalogExport {
    val entries = mutableListOf<AssetCatalogEntry>()
    val missing = mutableListOf<UnpublishableAsset>()
    for (asset in assets.values) {
        val entry = asset.catalogEntry()
        if (entry != null) {
            entries += entry
        } else {
            missing += UnpublishableAsset(asset.id, asset.kind)
        }
    }
    return CatalogExport(AssetCatalog.of(entries), missing.sortedBy { it.id })
}
