package dev.wildware.udea.assets

/**
 * Two floats, in world units.
 *
 * Not `com.badlogic.gdx.math.Vector2`, which the old asset tree used: this module has no LibGDX
 * on its classpath and putting one there would put it on the classpath of everything that reads an
 * asset (spec 4). Not `udea-core`'s `SpawnPosition` either - core does not depend on assets and
 * assets must not depend on core - so the conversion happens once, in the adapter that turns a
 * [Level] into spawns.
 *
 * Immutable, unlike `Vector2`, which matters more here than the type name does: the old
 * `EntityDefinition.position` was a mutable vector shared with whatever the level loader did to it.
 */
public data class Vec2(public val x: Float, public val y: Float)

/**
 * A field value inside authored asset data.
 *
 * The old tree stored these as `Map<String, Any>` (`Ability.params`) and as live Fleks `Component`
 * instances built by a running script host (`Blueprint.components`). Both are gone for the same
 * reason: spec 3.6 kills the runtime `BasicJvmScriptingHost`, so what ships in a `.udeapak` has to
 * be *data* that a reader can decode without evaluating anything, and `Any` is not a format.
 *
 * Closed on purpose, and small. A sealed hierarchy is what lets the pack encoder be exhaustive and
 * the validator report an unsupported field type at build time rather than at load. A game that
 * needs a richer field composes it out of [ListValue] and [StructValue], which is what the encoder
 * can actually write.
 */
public sealed interface AssetValue {

    /** `true` / `false`. */
    public data class BoolValue(public val value: Boolean) : AssetValue

    /** A whole number that fits in 32 bits. */
    public data class IntValue(public val value: Int) : AssetValue

    /** A whole number that does not. Tick counts and ids live here. */
    public data class LongValue(public val value: Long) : AssetValue

    /** A single-precision number. Damage, radii, durations in seconds. */
    public data class FloatValue(public val value: Float) : AssetValue

    /** Free text: a display name, a description. Never a path and never an asset id. */
    public data class TextValue(public val value: String) : AssetValue

    /** A resource file. Distinct from [TextValue] so the packer can find every file to pack. */
    public data class PathValue(public val value: ResPath) : AssetValue

    /**
     * A reference to another asset. Distinct from [TextValue] so the validator can check the
     * target exists and is the right kind, and so the packer can intern the slot.
     */
    public data class RefValue(public val value: Ref<*>) : AssetValue

    /** A position or a direction. */
    public data class VecValue(public val value: Vec2) : AssetValue

    /** An ordered list. Homogeneity is a validator's business, not this type's. */
    public data class ListValue(public val values: List<AssetValue>) : AssetValue

    /** A named group of values: a nested data class in the authored form. */
    public data class StructValue(public val fields: Map<String, AssetValue>) : AssetValue
}
