package dev.wildware.udea.assets

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.Snapshot
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.component.base.Transform

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
    val parent: AssetReference<Blueprint>? = null
) : Asset() {
    fun newInstance(world: World, init: EntityCreateContext.(Entity) -> Unit = {}) = world.entity {
        it += Transform()
        it += dev.wildware.udea.ecs.component.base.Blueprint(this@Blueprint)
//        it += DebugComponent(debugPhysics = true)
        init(this, it)
    }.apply { world.loadSnapshotOf(this, snapshot) }
}
