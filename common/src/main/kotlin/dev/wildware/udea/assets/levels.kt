package dev.wildware.udea.assets

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.UniqueId
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.system.AbilitySystem
import dev.wildware.udea.ecs.system.BackgroundDrawSystem
import dev.wildware.udea.ecs.system.Box2DSystem
import dev.wildware.udea.ecs.system.CameraTrackSystem
import dev.wildware.udea.ecs.system.CleanupSystem
import dev.wildware.udea.ecs.system.ControllerSystem
import dev.wildware.udea.ecs.system.NetworkClientSystem
import dev.wildware.udea.ecs.system.NetworkServerSystem
import dev.wildware.udea.ecs.system.ParticleSystemSystem
import dev.wildware.udea.ecs.system.SpriteBatchSystem
import dev.wildware.udea.uClass

/**
 * A level is a collection of entities and components that define a game level.
 * */
data class Level(
    /**
     * The systems that will be used to update the entities in this level.
     * */
    val systems: List<UClass<out IntervalSystem>> = listOf(
        BackgroundDrawSystem::class.uClass,
        Box2DSystem::class.uClass,
        CameraTrackSystem::class.uClass,
        AbilitySystem::class.uClass,
        CleanupSystem::class.uClass,
        ControllerSystem::class.uClass,
        SpriteBatchSystem::class.uClass,
        ParticleSystemSystem::class.uClass,
        NetworkClientSystem::class.uClass,
        NetworkServerSystem::class.uClass,
    ),

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
data class EntityDefinition(
    /**
     * Unique identifier
     * */
    val id: Long,

    /**
     * The name of this entity
     */
    val name: String = "Entity $id",

    /**
     * List of components that will be attached to the entity.
     * Components define the entity's behavior and properties.
     */
    val components: List<Component<out Any>> = listOf(Transform()),

    /**
     * List of tags associated with this entity.
     * Tags can be used for grouping and identifying entities.
     */
    val tags: List<UniqueId<Any>> = emptyList(),

    /**
     * The parent blueprint for this entity.
     * */
    val blueprint: AssetReference<Blueprint>? = null,
)
