package dev.wildware.udea.assets

import com.github.quillraven.fleks.Snapshot

/**
 * A blueprint represents a template for creating entities in the game world.
 * It contains essential information like name, state snapshot, and optional parent blueprint
 * that can be used to instantiate game entities with predefined properties.
 */
class Blueprint(
    /**
     * The unique identifier name for this blueprint.
     */
    val name: String,

    /**
     * The snapshot containing the entity's component state and configuration.
     */
    val snapshot: Snapshot,

    /**
     * Optional parent blueprint that this blueprint inherits from.
     * Allows for blueprint hierarchies and component inheritance.
     */
    val parent: Asset<Blueprint>? = null
) {
    /**
     * Companion object defining the asset type for blueprints.
     */
    companion object : AssetType<Blueprint>() {
        override val id: String = "blueprint"
    }
}
