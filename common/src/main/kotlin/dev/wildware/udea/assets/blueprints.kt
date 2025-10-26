package dev.wildware.udea.assets

import com.github.quillraven.fleks.*
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.base.Blueprint as BlueprintComponent

val EmptySnapshot = Snapshot(emptyList(), emptyList())

/**
 * A blueprint represents a template for creating entities in the game world.
 * It contains essential information like name, state snapshot, and optional parent blueprint
 * that can be used to instantiate game entities with predefined properties.
 */
data class Blueprint(
    /**
     * A list of components on this blueprint.
     * */
    val components: () -> List<Component<out Any>> = { listOf(Transform()) },

    /**
     * A list of tags on this blueprint.
     * */
    val tags: List<UniqueId<Any>> = emptyList(),

    /**
     * Optional parent blueprint that this blueprint inherits from.
     * Allows for blueprint hierarchies and component inheritance.
     */
    val parent: AssetReference<Blueprint>? = null
) : Asset() {
    fun newInstance(world: World, init: EntityCreateContext.(Entity) -> Unit = {}) = world.entity {
        it += BlueprintComponent(this@Blueprint)

        it += components()
        it += tags
        init(this, it)
    }
}
