package dev.wildware.udea.assets

/** What an ability is called and what it says it does, for a UI to draw. */
public data class AbilityDisplay(
    public val name: String,
    public val description: String,
) {
    init {
        require(name.isNotBlank()) { "an ability's display name must not be blank" }
    }
}

/**
 * One ability: which implementation runs it, and every number that tunes it.
 *
 * This is the asset an agent edits in the tuning loop spec 3.6 describes - change the damage,
 * reload, rewind, watch the same fight with the new numbers - so every field here is data and none
 * of it is behaviour. [exec] names the class that *is* the behaviour.
 *
 * ## The effect references
 *
 * [cooldown] and [costs] are `Ref<*>` rather than `Ref<GameplayEffect>`, and the reason has
 * changed. It used to be that the effect kind "belongs to `udea-gas`"; it does not - what belongs
 * there is `GameplayEffectDef`, which holds interned ids only a running game has - and
 * [GameplayEffect] is declared in this module beside this class. What keeps the star is narrower:
 * these two are the fields a *game's own* effect kind would land in, and `AssetCodecs` binds them
 * with `GameplayEffect::class` today. The star is only the *static* half:
 * `reference<GameplayEffect>("...")` records `GameplayEffect::class` in [Ref.expected], so
 * resolution type-checks exactly, and the build-time validator checks the target kind besides.
 */
public data class Ability(
    override val id: AssetId,
    /** The class that executes it. `AbilityExec` lives in `udea-gas`; here it is a name. */
    public val exec: UClass<Any>,
    public val display: AbilityDisplay? = null,
    /** Tuning values the exec reads by name. Typed values, not the old `Map<String, Any>`. */
    public val params: Map<String, AssetValue> = emptyMap(),
    /** The gameplay effect applied to put this ability on cooldown. */
    public val cooldown: Ref<*>? = null,
    /** Gameplay effects that must apply for the activation to be paid for. */
    public val costs: List<Ref<*>> = emptyList(),
    /** Tags that block activation while present on the owner. */
    public val blockedBy: List<GameplayTagName> = emptyList(),
    /** Tags this ability grants while active. */
    public val tags: List<GameplayTagName> = emptyList(),
    /** Magnitudes the caller supplies at activation, keyed by tag. */
    public val setByCaller: Map<GameplayTagName, Float> = emptyMap(),
    /** World units, or `null` for an ability with no range check. */
    public val range: Float? = null,
    /** Whether activation suppresses the owner's animation state machine. */
    public val blockAnimations: Boolean = false,
) : AssetData {

    init {
        require(range == null || (range > 0F && range.isFinite())) {
            "ability '$id' has range $range; use null for an ability with no range check"
        }
    }
}
