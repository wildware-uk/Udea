package dev.wildware.udea.assets

/**
 * One ability a [Character] is granted, and the slot tags it is granted under.
 *
 * `level` is the authored rank. It is an `Int` and not an enum because the old corpus wrote
 * `abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))` and the slot is
 * the *tag*; the level is the GAS ability level, which is a number in every game that has one.
 */
public data class AbilitySpec(
    public val ability: Ref<Ability>,
    /** Slot and category tags, e.g. `Slot.A`. */
    public val tags: List<GameplayTagName> = emptyList(),
    public val level: Int = 1,
) {
    init {
        require(level >= 1) { "an ability spec's level starts at 1, was $level" }
    }
}

/**
 * A playable or AI unit: a spawn recipe with art, audio, stats and a loadout on it.
 *
 * ## What this closes
 *
 * `character` was [dev.wildware.udea.assets.compiler.AssetKind.Unpublishable] - a DSL word with
 * no runtime type - from the day the pipeline was written, and it cost more than a typed
 * accessor. Three things followed from it and all three are gone with this class:
 *
 * 1. A level whose entities named characters packed with every `blueprint` field **dropped**
 *    (`UDEA0013` per entity), so the migrated corpus could compile and could never be loaded.
 *    See [SpawnRecipe] for why the slot's type, not this class's absence, was the mechanism.
 * 2. `moba` carried the roster twice: `assets/character/<name>.udea.kts` held the art and
 *    `MobaUnits`/`MobaBlueprints` held the stats and the loadout, with nothing checking that the
 *    two agreed about which characters exist.
 * 3. "Which of these animations is the walk" had nowhere typed to live, so `moba` carried it in
 *    an **id suffix** convention (`character/orc_walk` is the `walk` of `orc`) that no validator
 *    could check. [animations] is that map, and it is checked: every value is stamped
 *    `Ref<SpriteAnimation>`, so a role pointing at a sound cue is a build error.
 *
 * ## Attributes are names, not a class
 *
 * The old DSL took `attributeSet = { CharacterAttributeSet(initHealth = 500F, ...) }`, a lambda
 * constructing a *game* class, which is what made an asset edit a compile dependency on the game.
 * Here they are `name -> value`; the class that interprets `health` and `magicResist` is the
 * game's, and `udea-gas` interns the names once at load.
 */
public data class Character(
    override val id: AssetId,
    /** World-space scale of the unit, as the old `character(size = ...)` meant it. */
    public val size: Float = 1F,
    /** Starting health. Redundant with an `attributes["health"]` and authored by both corpora. */
    public val health: Float = 100F,
    /** The set every one of [animations] is expected to come from, when the author named one. */
    public val animationSet: Ref<SpriteAnimationSet>? = null,
    /** Role (`idle`, `walk`, `attack`, ...) to the animation played for it. */
    public val animations: Map<String, Ref<SpriteAnimation>> = emptyMap(),
    /** Event (`attack`, `hit`, `death`) to the cue played for it. */
    public val sounds: Map<String, Ref<SoundCue>> = emptyMap(),
    /** Initial attribute values by authored name. */
    public val attributes: Map<String, Float> = emptyMap(),
    override val components: List<ComponentSpec> = emptyList(),
    override val tags: List<EntityTagName> = emptyList(),
    /** The loadout, in the order the author granted it. */
    public val abilities: List<AbilitySpec> = emptyList(),
) : SpawnRecipe {

    init {
        require(size > 0F && size.isFinite()) {
            "character '$id' has size $size; a unit with no size is drawn as nothing"
        }
        require(health > 0F && health.isFinite()) {
            "character '$id' starts at $health health, so it is dead on the tick it spawns"
        }
    }
}
