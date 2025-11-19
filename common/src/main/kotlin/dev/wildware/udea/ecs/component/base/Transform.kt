package dev.wildware.udea.ecs.component.base

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.NetworkComponent.Companion.configureNetwork
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.network.serde.UdeaSync

/**
 * Represents the spatial transform of an entity in 2D space.
 * This component handles position, rotation, and scale of game objects.
 * It is networked to synchronize transform data across clients.
 */
@UdeaNetworked
data class Transform(
    /** The position of the entity in 2D space */
    @UdeaSync
    val position: Vector2 = Vector2(),
    /** The rotation of the entity in degrees */
    @UdeaSync
    var rotation: Float = 0F,
    /** The scale of the entity in both X and Y axes */
    @UdeaSync
    val scale: Vector2 = Vector2(1f, 1f),
) : Component<Transform> {
    override fun type() = Transform

    companion object : UdeaComponentType<Transform>(
        networkComponent = configureNetwork()
    )
}
