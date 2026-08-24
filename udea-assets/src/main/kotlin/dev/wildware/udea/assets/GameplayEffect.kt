package dev.wildware.udea.assets

/**
 * How a modifier combines with the attribute value it lands on.
 *
 * The names are `dev.wildware.udea.gas.ModifierType`'s, and the duplication is deliberate rather
 * than an oversight: `udea-gas` depends on `udea-core` and this module depends on nothing, so a
 * shared enum would put a simulation module on every asset compile classpath. `GasAssetParityTest`
 * compares the two name sets, so the copy cannot drift silently.
 */
public enum class ModifierKind {
    /** `value + magnitude`. */
    Additive,

    /** `value * magnitude`. */
    Multiplicative,

    /** `magnitude`, discarding what was there. */
    Override,
}

/**
 * How long an applied effect lasts, as an author declares it.
 *
 * Not `dev.wildware.udea.gas.GameplayEffectDuration`: that one is denominated in **ticks** and
 * resolves a caller-supplied magnitude, both of which need a tick rate and a running simulation.
 * This is the authored half - `instant()`, `infinite()`, `duration("Data.Duration")` - and the
 * conversion happens once, in the game's loader, through `ticksFromSeconds`.
 */
public sealed interface EffectDuration {

    /** Applies once and is over. */
    public data object Instant : EffectDuration

    /** Never expires on its own. */
    public data object Infinite : EffectDuration

    /** Lasts as long as the magnitude the caller staged under [tag] says. */
    public data class SetByCaller(public val tag: GameplayTagName) : EffectDuration
}

/** Where an effect's magnitude comes from. */
public sealed interface EffectMagnitude {

    /** A value the activating entity stages at cast time, keyed by [tag]. */
    public data class SetByCaller(public val tag: GameplayTagName) : EffectMagnitude

    /** A value read from an attribute of the target, by authored name. */
    public data class Attribute(public val name: String) : EffectMagnitude
}

/**
 * One gameplay effect, as the asset graph declares it.
 *
 * ## Why this is here and not in `udea-gas`
 *
 * `Ability`'s KDoc said the kind "belongs to `udea-gas`", and it does not - what belongs there is
 * `GameplayEffectDef`, which holds an interned `AttributeId`, a `TagSet` and an `IntArray` of cue
 * ids. Those are *interning results*: they exist only once a game has an attribute table and a
 * tag table, so they cannot be what a `.udea.kts` declares and cannot be decoded from a bundle
 * without one. This is the authored record, with names where the def has indices, and it sits
 * beside [Ability] because that is the kind that references it.
 *
 * Making it publishable is the last third of what kept two asset roots in this repository:
 * `ability/gameplay_effects.udea.kts` declared eight of these, `MobaEffects` wrote the same eight
 * out in Kotlin, and nothing compared them.
 *
 * [period] is **seconds**, as authored, and is the one field a reader must convert rather than
 * use. Ticks are the simulation's unit and seconds are the designer's; the conversion is
 * `dev.wildware.udea.gas.ticksFromSeconds` and it is deterministic by construction.
 */
public data class GameplayEffect(
    override val id: AssetId,
    public val duration: EffectDuration = EffectDuration.Instant,
    /** The attribute modified, by authored name, or `null` for a tag-only effect. */
    public val target: String? = null,
    public val modifierType: ModifierKind = ModifierKind.Additive,
    public val magnitude: EffectMagnitude? = null,
    /** Seconds between periodic applications, or `0` for none. */
    public val period: Float = 0F,
    /** Cue names emitted on application. Names, because a cue id is an interning result. */
    public val cues: List<String> = emptyList(),
    /** Tags this effect carries while applied. */
    public val tags: List<GameplayTagName> = emptyList(),
) : AssetData {

    init {
        require(period >= 0F && period.isFinite()) {
            "gameplay effect '$id' has period $period seconds; use 0 for a non-periodic effect"
        }
        require(!(duration is EffectDuration.Instant && period > 0F)) {
            "gameplay effect '$id' is instant and periodic at once; an instant effect has no " +
                "second application"
        }
    }
}

/**
 * A short-lived visual: an animation set, which of its animations to play, and for how long.
 *
 * A game kind in the old tree - `example/.../assets/Effect.kt` declared it - and an engine one
 * here for the same reason [Character] is: it was `Unpublishable`, so `effects/heal_effect` packed
 * as an opaque record and the priest's heal had no art any loader could find by type.
 *
 * [animation] is a name inside [animationSet] rather than a second `Ref`, because that is what the
 * corpus wrote and because the set is the unit of art: naming an animation outside it would let a
 * bundle hold an effect whose frames are in a different atlas page.
 */
public data class Effect(
    override val id: AssetId,
    public val animationSet: Ref<SpriteAnimationSet>,
    /** Which animation of [animationSet] plays, by the last word of its id. */
    public val animation: String,
    /** Seconds the visual lives for. */
    public val duration: Float,
) : AssetData {

    init {
        require(animation.isNotBlank()) { "effect '$id' names no animation in its set" }
        require(duration > 0F && duration.isFinite()) {
            "effect '$id' lasts $duration seconds, so it is removed on the tick it is spawned"
        }
    }
}
