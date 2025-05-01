package dev.wildware.udea.assets

import com.github.quillraven.fleks.Snapshot

val EmptySnapshot = Snapshot(emptyList(), emptyList())

/**
 * A blueprint represents a template for creating entities in the game world.
 * It contains essential information like name, state snapshot, and optional parent blueprint
 * that can be used to instantiate game entities with predefined properties.
 */
class Blueprint(
    /**
     * The snapshot containing the entity's component state and configuration.
     */
    val snapshot: Snapshot = EmptySnapshot,

    /**
     * Optional parent blueprint that this blueprint inherits from.
     * Allows for blueprint hierarchies and component inheritance.
     */
    val parent: AssetRefence<Blueprint>? = null
): Asset()
