package dev.wildware.udea.assets

import kotlin.reflect.KClass

/**
 * A typed reference to an asset, resolved through an [AssetRegistry].
 *
 * Two things the old `AssetRefImpl` did not have, and one it had that is gone:
 *
 * - **[expected], a type token.** `reference<Blueprint>("character/orc_elite")` records
 *   `Blueprint::class`, so pointing a blueprint reference at a sound cue fails at resolution with
 *   [AssetTypeMismatchException] naming the id and both kinds, instead of surfacing as a
 *   `ClassCastException` inside whatever code first read `.value`
 *   (`common/.../assets.kt:36-43`).
 * - **An interned slot.** After the first resolution, `registry[ref]` is an array read rather than
 *   a hash lookup into a global map (Trello #32). The slot is the [AssetIndex] the pack assigned.
 * - **No `value` property.** The old one resolved itself through the `Assets` global with a
 *   `by lazy`, which is what made assets a global in the first place. A reference is data; the
 *   thing that turns it into an asset is a registry you were handed.
 *
 * ## Identity
 *
 * Equality is `(id, expected)`. The cached slot is *not* part of it: the same reference, held by
 * two entities and resolved against two registries, is still the same reference, and a `Ref` that
 * changed its hash code the first time somebody resolved it would corrupt any `HashSet` it was
 * already in.
 *
 * ## Thread safety
 *
 * The cache is one nullable field holding an immutable [RefBinding]. That is deliberate rather
 * than incidental: a pair of plain `index`/`layout` fields can be published out of order, and a
 * reader that saw a fresh layout beside a stale index would be handed the *wrong asset* with no
 * error anywhere. A binding's fields are final, so a thread that sees the binding at all sees it
 * complete. Racing threads either miss and redo an idempotent lookup, or share one; a `Ref`
 * resolved against a different registry misses on layout identity and re-resolves.
 */
public class Ref<T : AssetData> internal constructor(
    /** The asset this points at. */
    public val id: AssetId,
    /**
     * The kind the holder expects. `KClass<out AssetData>` rather than `KClass<T>` so the field is
     * a plain token to compare against; [assetRef] is what guarantees it agrees with `T`.
     */
    public val expected: KClass<out AssetData>,
) {

    /** The interned slot and the interning space it belongs to, or `null` before first use. */
    @JvmField
    internal var binding: RefBinding? = null

    /** The interned slot, or `null` when this reference has not been resolved yet. Tests. */
    internal val resolvedIndex: AssetIndex? get() = binding?.let { AssetIndex(it.index) }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Ref<*> && id == other.id && expected == other.expected)

    override fun hashCode(): Int = 31 * id.hashCode() + expected.hashCode()

    override fun toString(): String = "Ref<${expected.simpleName}>($id)"
}

/**
 * One resolved slot, immutable so that publishing it is safe without synchronisation.
 *
 * [layout] is compared by identity, never by content, and that is what makes a cached slot safe in
 * a JVM holding more than one registry: a `Ref` resolved against registry A and then read through
 * registry B misses this check and re-resolves, rather than silently returning whatever asset B
 * keeps at A's slot.
 */
internal class RefBinding(@JvmField val layout: AssetLayout, @JvmField val index: Int)

/**
 * A reference to [id], expected to be a [T].
 *
 * The one place a [Ref] is built with a type token, and the reason [Ref.expected] can be trusted
 * to agree with `T`: [expected] is `KClass<T>` - invariant - so no other class can be passed. The
 * `.udeapak` reader uses this; authored code uses [reference], which is this with `T::class`
 * filled in by the compiler.
 */
public fun <T : AssetData> assetRef(id: AssetId, expected: KClass<T>): Ref<T> = Ref(id, expected)

/**
 * A reference to the asset called [id], expected to be a [T].
 *
 * The authoring form: `reference<Blueprint>("character/orc_elite")`. Scripts keep using string ids
 * on purpose - spec 3.6, "scripts never consume generated accessors" - so this is what a
 * `.udea.kts` writes, and `.kt` code writes the same thing or a generated `GameAssets` accessor
 * that expands to it.
 *
 * The string is checked for shape here ([AssetId]) and for existence at build time by the asset
 * validator. Nothing about this call reaches a classpath, a script host or a global.
 */
public inline fun <reified T : AssetData> reference(id: String): Ref<T> = assetRef(AssetId(id), T::class)

/** [reference] for a caller that already holds an [AssetId]. */
public inline fun <reified T : AssetData> reference(id: AssetId): Ref<T> = assetRef(id, T::class)
