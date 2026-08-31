package dev.wildware.udea.assets.compiler

import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.Axis2D
import dev.wildware.udea.assets.Axis2DBinding
import dev.wildware.udea.assets.Binding
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.Character
import dev.wildware.udea.assets.Control
import dev.wildware.udea.assets.Effect
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.GameplayEffect
import dev.wildware.udea.assets.Item
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteAnimationSet
import dev.wildware.udea.assets.SpriteSheet
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
 * [Unpublishable] exists because a **game declares its own kinds** - `asset("particle", ...)`,
 * the generic escape - and the honest treatment of one is a named diagnostic rather than an
 * invented FQN. An entry in a compile classpath's catalog is a promise that a class of that name
 * exists for the checker to resolve; writing `dev.wildware.udea.assets.Particle` because the DSL
 * word is `particle` would make the checker go quiet on every reference to it (an unresolvable
 * kind is a silent case by contract), which is worse than the declaration being absent, because
 * it is absent *and* reported as present.
 *
 * `character`, `gameplayEffect` and `effect` used to be the live examples. They are
 * [Declared] now - [dev.wildware.udea.assets.Character],
 * [dev.wildware.udea.assets.GameplayEffect] and [dev.wildware.udea.assets.Effect] - and the
 * argument above is exactly why the fix was to *add the types* rather than to name them anyway.
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

/**
 * Which declared kinds may be stored in a slot that declares another.
 *
 * ## Why this is one object and not a comparison at each site
 *
 * Two passes decide "is this reference the right kind": [dev.wildware.udea.assets.compiler.validate.ReferenceTypeValidator]
 * (pass 3, which reports `UDEA0013`) and `GraphPacker` (pass 4, which reports the same rule and
 * then *drops the field*). They must agree exactly - a pass-4 drop that pass 3 did not report is
 * a bundle silently missing a reference the build called clean - and until this object existed
 * they agreed only because both spelled `actual == expected`.
 *
 * That spelling was also the bug. `EntityDefinition.blueprint` is a
 * `Ref<dev.wildware.udea.assets.SpawnRecipe>`, satisfied by both `Blueprint` and [Character], so
 * exact equality reported every entity in a migrated level as a kind mismatch and packed a level
 * with no entities in it.
 *
 * ## Names, not classes
 *
 * A kind crosses the worker boundary as a fully qualified **name**, and a game's own kind is a
 * name this module cannot load. So assignability is a lookup, derived from the engine's own
 * classes - nothing is transcribed, so a type that grows an interface cannot forget to appear
 * here - and a name that is not the engine's satisfies only itself. That is the conservative
 * answer: accepting an unknown reference would move the failure to bundle-open time, where
 * `AssetRegistry` refuses it with the game already running.
 */
public object AssetKindHierarchy {

    /** Every [AssetData] type this module can name, by fully qualified name. */
    public val KNOWN: Map<String, KClass<out AssetData>> = listOf(
        SpriteSheet::class,
        SoundCue::class,
        SpriteAnimation::class,
        SpriteAnimationSet::class,
        Blueprint::class,
        Level::class,
        GameConfig::class,
        Control::class,
        Axis2D::class,
        Binding::class,
        Axis2DBinding::class,
        Ability::class,
        Character::class,
        GameplayEffect::class,
        Effect::class,
        Item::class,
    ).associateBy { requireNotNull(it.qualifiedName) }

    /** For each known kind, every name it is also an instance of. */
    private val assignable: Map<String, Set<String>> =
        KNOWN.mapValues { (_, type) -> supertypesOf(type.java) }

    /**
     * Whether a declaration of kind [actual] may be stored in a slot declared as [wanted].
     *
     * `null` on either side is `false` from this function's point of view and **silence** at
     * both call sites: a slot that constrains nothing and a target with no runtime kind are
     * cases neither pass may invent an answer for, so each returns before asking.
     */
    public fun satisfies(actual: String?, wanted: String?): Boolean = when {
        actual == null || wanted == null -> false
        actual == wanted -> true
        else -> wanted in assignable[actual].orEmpty()
    }

    private fun supertypesOf(type: Class<*>): Set<String> = buildSet {
        add(type.name)
        type.interfaces.forEach { addAll(supertypesOf(it)) }
        type.superclass?.let { addAll(supertypesOf(it)) }
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
