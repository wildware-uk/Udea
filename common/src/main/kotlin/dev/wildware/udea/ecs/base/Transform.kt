package dev.wildware.udea.ecs.base

import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.NetworkComponent.Companion.configureNetwork
import dev.wildware.udea.ecs.UdeaComponentType
import dev.wildware.udea.math.Vector2
import dev.wildware.udea.network.Networked

/**
 * Represents the spatial transform of an entity in 2D space.
 * This component handles position, rotation, and scale of game objects.
 * It is networked to synchronize transform data across clients.
 */
@Networked
data class Transform(
    /** The position of the entity in 2D space */
    val position: Vector2 = Vector2(),
    /** The rotation of the entity in degrees */
    var rotation: Float = 0F,
    /** The scale of the entity in both X and Y axes */
    val scale: Vector2 = Vector2(1f, 1f),
) : Component<Transform> {
    override fun type() = Transform

    companion object : UdeaComponentType<Transform>(
        networkComponent = configureNetwork()
    )
}
