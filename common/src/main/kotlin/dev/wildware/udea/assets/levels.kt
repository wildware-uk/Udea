package dev.wildware.udea.assets

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.UniqueId
import dev.wildware.udea.dsl.CreateDsl
import dev.wildware.udea.ecs.system.*
import kotlin.reflect.KClass

/**
 * A level is a collection of entities and components that define a game level.
 * */
data class Level(
    /**
     * The systems that will be used to update the entities in this level.
     * */
    val systems: List<KClass<out IntervalSystem>> = emptyList(),

    /**
     * Component snapshots that will be placed on entities.
     * */
    val entities: List<EntityDefinition> = emptyList()
) : Asset() {
    fun nextEntityId(): Long {
        return entities.maxOfOrNull { it.id }?.plus(1) ?: 0L
    }
}

/**
 * Defines an entity and its components for use in a level.
 * EntityDefinition provides a blueprint for creating entities with specific components and tags.
 */
@CreateDsl
data class EntityDefinition(
    /**
     * The name of this entity
     */
    val name: String = "Entity",

    /**
     * Lazy list of components that will be attached to the entity.
     * Components define the entity's behavior and properties.
     */
    val components: LazyList<Component<out Any>> = emptyLazyList(),

    /**
     * List of tags associated with this entity.
     * Tags can be used for grouping and identifying entities.
     */
    val tags: List<UniqueId<Any>> = emptyList(),

    /**
     * The parent blueprint for this entity.
     * */
    val blueprint: AssetReference<Blueprint>? = null,
) {
    /**
     * Unique identifier
     * */
    var id: Long = -1L
}
