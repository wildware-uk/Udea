package dev.wildware.udea.compiler.fir

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * The two names [UdeaAssetReferenceChecker] binds to, and why there are two.
 *
 * ### `@AssetRef` is the contract, `reference` is the fallback
 *
 * The intended binding is the annotation: any value parameter marked `@AssetRef` holds an
 * asset id, whoever declared the function. That is the shape `common`'s
 * `fun <T : Asset<T>> reference(@AssetRef path: String)` already documents ("adds
 * compile-time validation") and has never delivered.
 *
 * The annotation alone is not enough today, and the reason is worth stating rather than
 * discovering later: `common`'s `@AssetRef` is declared `@Retention(AnnotationRetention.SOURCE)`,
 * so it is **not written into the class file at all**. A checker in a *downstream* module sees
 * the deserialised `reference` symbol with no annotations on its parameters, and matching by
 * annotation alone would silently validate nothing outside the module that declares it — the
 * exact silent-failure shape section 1 of the engineering standards forbids. So the callable
 * id is matched as well.
 *
 * When `udea-assets` mints its own `@AssetRef` with `BINARY` retention (issue #84 owns that
 * annotation; this module must not invent it), the annotation path starts working across
 * module boundaries on its own and the callable-id path becomes belt-and-braces for the
 * legacy declaration. Both are kept: neither is a superset of the other.
 */
internal object UdeaAssetReferences {

    /** The package both the legacy `common` declaration and the `udea-assets` one live in. */
    val PACKAGE: FqName = FqName("dev.wildware.udea.assets")

    /**
     * `@AssetRef`, the value-parameter marker.
     *
     * Matched by name, like every other annotation this plugin binds to: the annotation is on
     * the *compiled module's* classpath, not on the plugin's.
     */
    val ASSET_REF: ClassId = ClassId(PACKAGE, Name.identifier("AssetRef"))

    /**
     * Top-level `reference(...)`.
     *
     * One [CallableId] covers both overloads — the plain one and the `ListBuilder` extension —
     * because a top-level extension's callable id is its package plus its name.
     */
    val REFERENCE: CallableId = CallableId(PACKAGE, Name.identifier("reference"))
}
