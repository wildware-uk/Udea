package dev.wildware.udea.assets.compiler.gen

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
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteAnimationSet
import dev.wildware.udea.assets.SpriteSheet
import kotlin.reflect.KClass

/**
 * The DSL word each declaration function uses, and the runtime type it produces.
 *
 * Pass 1 is syntactic: `UdeaDeclarationScanner` sees `spriteSheet(name = "orc_idle", ...)` and
 * records the *word* `spriteSheet`, because it has no classpath and no resolution - that is the
 * whole reason it can run before anything is compiled. The accessor generator needs a *type*
 * to write `Ref<SpriteSheet>`, so somewhere the word has to become the type, and this is that
 * place.
 *
 * ## It is a `KClass`, not a string
 *
 * `docs/contracts/asset-index.md` settles this for the catalog and the same argument applies
 * here: a table of hand-written fully qualified names is a table that goes stale on the first
 * rename, silently, because nothing type-checks a string. Taking the name off the `KClass`
 * means a rename in `udea-assets` moves both sides at once or fails to compile.
 *
 * ## A word missing from this table is not an error
 *
 * Anything declared through `asset(kind, ...)`, the generic escape, has no runtime type - see
 * `AssetKind.Unpublishable`. It gets no generated accessor, which is the honest outcome:
 * there is no type to give the `Ref` a parameter of. They are still packed, still validated,
 * and still reachable through `reference("...")` from a script, which is where the tree
 * actually names them (spec 3.6: scripts never consume generated accessors).
 */
public object DslKinds {

    /** DSL word to the `AssetData` it produces. */
    public val TYPES: Map<String, KClass<out AssetData>> = mapOf(
        "ability" to Ability::class,
        "spriteSheet" to SpriteSheet::class,
        "spriteAnimation" to SpriteAnimation::class,
        "spriteAnimationSet" to SpriteAnimationSet::class,
        "soundCue" to SoundCue::class,
        "blueprint" to Blueprint::class,
        "level" to Level::class,
        "gameConfig" to GameConfig::class,
        "control" to Control::class,
        "axis2D" to Axis2D::class,
        "binding" to Binding::class,
        "axis2DBinding" to Axis2DBinding::class,
        "character" to Character::class,
        "gameplayEffect" to GameplayEffect::class,
        "effect" to Effect::class,
    )

    /** The type for [word], or null when the word has no runtime type. */
    public operator fun get(word: String): KClass<out AssetData>? = TYPES[word]

    /** Fully qualified name of [word]'s type, or null. What the asset index publishes. */
    public fun fqnOf(word: String): String? = TYPES[word]?.qualifiedName

    /** Words with a runtime type, sorted. */
    public val WORDS: List<String> = TYPES.keys.sorted()
}
