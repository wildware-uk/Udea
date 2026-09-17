package dev.wildware.udea.assets

/**
 * An asset a level may place: something that says what components an entity starts with.
 *
 * ## Why this interface exists at all
 *
 * [EntityDefinition.blueprint] used to be a `Ref<Blueprint>`, and that was the sentence that kept
 * two asset roots in this repository. The old game's `character(...)` declaration *was* a
 * blueprint - it inlined a fixed component list at build time and returned one - so every entity
 * in the migrated `level/test_level` named a `character/...`. With `character` unpublishable and
 * the slot typed as exactly `Blueprint`, packing that level produced twenty-seven `UDEA0013`s and
 * dropped every `blueprint` field, so the bundle held a level whose entities named nothing.
 *
 * The honest fix is not to widen the slot to `AssetData` - that would let a level spawn a sound
 * cue - and not to make `character` claim to be a `Blueprint`, whose fields it does not have.
 * It is to name the property both kinds really share: *this is a recipe for an entity*.
 * [Blueprint] and [Character] implement it, `SoundCue` does not, and
 * `dev.wildware.udea.assets.compiler.pack.GraphPacker` checks a reference against the whole
 * supertype set rather than against one fully qualified name - so `reference("character/orc")` in
 * an entity slot is accepted and `reference("character/orc_idle_sheet")` is still `UDEA0013`.
 *
 * ## Not sealed
 *
 * For [AssetData]'s reason: a game declares its own kinds, and a game's own spawnable kind must
 * be able to implement this without editing this module.
 */
public interface SpawnRecipe : AssetData {

    /** Every component an entity built from this recipe starts with, in application order. */
    public val components: List<ComponentSpec>

    /** Every Fleks tag it starts with. */
    public val tags: List<EntityTagName>
}
