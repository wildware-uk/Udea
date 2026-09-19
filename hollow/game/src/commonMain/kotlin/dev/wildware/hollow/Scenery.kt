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
 */
@Serializable
public enum class Prop(
    /** What sort of thing this is. */
    public val kind: PropKind,
) {
    GROUND(PropKind.Ground),

    PINE_TALL_A(PropKind.Tree),
    PINE_TALL_B(PropKind.Tree),
    PINE_ROUND_C(PropKind.Tree),
    PINE_ROUND_E(PropKind.Tree),
    OAK(PropKind.Tree),
    TREE_DEFAULT(PropKind.Tree),
    TREE_DETAILED(PropKind.Tree),
    TREE_FAT(PropKind.Tree),

    STONE_LARGE_A(PropKind.Stone),
    STONE_LARGE_C(PropKind.Stone),
    STONE_TALL_A(PropKind.Stone),
    STONE_TALL_E(PropKind.Stone),
    STONE_SMALL_A(PropKind.Stone),
    STONE_SMALL_C(PropKind.Stone),

    STUMP(PropKind.Wood),
    LOG(PropKind.Wood),
    FENCE(PropKind.Wood),

    GRASS(PropKind.Plant),
    GRASS_LARGE(PropKind.Plant),
    BUSH(PropKind.Plant),
    BUSH_LARGE(PropKind.Plant),
    MUSHROOMS(PropKind.Plant),
    FLOWER_YELLOW(PropKind.Plant),
    FLOWER_PURPLE(PropKind.Plant),
}

/** What sort of thing a [Prop] is. */
public enum class PropKind {
    Ground,
    Tree,
    Stone,
    Wood,
    Plant,
}
