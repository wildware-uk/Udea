package dev.wildware.hollow

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import kotlinx.serialization.Serializable

/**
 * A piece of the clearing that stands still: the ground, a tree, a stone, a log.
 *
 * It says only **which** [Prop] the entity is. Where it stands is its `Transform3D`, which every
 * entity in Hollow has, and what it looks like is presentation's business: `ScenerySystem` gives the
 * entity the `ModelRenderer` for its prop, so the simulation - and a headless server, and a level
 * file - never holds a model, a texture or anything else from `udea-render`.
 *
 * `@Serializable` so a level file saves it (issue #191). Not `@Replicated`: a client loads the same
 * level as the server, so nothing about a prop needs the wire, and a `@Replicated` component would
 * take an id in `net-components.lock` ahead of every `moba` component, renumbering them all.
 */
@Serializable
public class Scenery(
    /** What this entity is drawn as. A `var` so an editor can swap one prop for another. */
    public var prop: Prop = Prop.GROUND,
) : Component<Scenery> {

    override fun type(): ComponentType<Scenery> = Scenery

    override fun toString(): String = "Scenery($prop)"

    public companion object : ComponentType<Scenery>()
}

/**
 * Every kind of static prop the clearing can hold, one per model asset in
 * `assets/models/nature.udea.kts` (the CC0 Kenney Nature Kit, plus the ground).
 *
 * An enum rather than an asset id in the component: the set is closed, it is saved in a level by
 * name, and [Prop.kind] groups it for anything that asks "is this a tree" without a string match.
 *
 * ## What a character walks into
 *
 * [blockRadius] is what `ClearingBodySystem` builds a static collision circle from (issue #250),
 * **in the model's own units**: an entity's uniform `Transform3D` scale multiplies it, so one
 * number covers a sapling and the same tree planted at seven times the size.
 *
 * The numbers are the models' own footprints, read out of the `.glb` accessor bounds, with one
 * adjustment: a tree's is narrowed to about a fifth, because a Kenney tree's footprint is its
 * canopy and a character walks under a canopy and into a trunk. Zero means a character walks
 * straight through, which is right for grass and for the ground itself.
 */
@Serializable
public enum class Prop(
    /** What sort of thing this is. */
    internal val kind: PropKind,
    /** The radius a character is stopped at, in model units. Zero for a prop it walks through. */
    internal val blockRadius: Float,
) {
    GROUND(PropKind.Ground, 0f),

    PINE_TALL_A(PropKind.Tree, 0.05f),
    PINE_TALL_B(PropKind.Tree, 0.05f),
    PINE_ROUND_C(PropKind.Tree, 0.06f),
    PINE_ROUND_E(PropKind.Tree, 0.06f),
    OAK(PropKind.Tree, 0.08f),
    TREE_DEFAULT(PropKind.Tree, 0.08f),
    TREE_DETAILED(PropKind.Tree, 0.10f),
    TREE_FAT(PropKind.Tree, 0.09f),

    STONE_LARGE_A(PropKind.Stone, 0.51f),
    STONE_LARGE_C(PropKind.Stone, 0.53f),
    STONE_TALL_A(PropKind.Stone, 0.49f),
    STONE_TALL_E(PropKind.Stone, 0.26f),
    STONE_SMALL_A(PropKind.Stone, 0.18f),
    STONE_SMALL_C(PropKind.Stone, 0.18f),

    STUMP(PropKind.Wood, 0.19f),
    LOG(PropKind.Wood, 0.40f),
    FENCE(PropKind.Wood, 0.50f),

    GRASS(PropKind.Plant, 0f),
    GRASS_LARGE(PropKind.Plant, 0f),
    BUSH(PropKind.Plant, 0f),
    BUSH_LARGE(PropKind.Plant, 0f),
    MUSHROOMS(PropKind.Plant, 0f),
    FLOWER_YELLOW(PropKind.Plant, 0f),
    FLOWER_PURPLE(PropKind.Plant, 0f),
}

/** What sort of thing a [Prop] is. */
internal enum class PropKind {
    Ground,
    Tree,
    Stone,
    Wood,
    Plant,
}
