package dev.wildware.udea.ecs.component.base

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.NetworkComponent.Companion.configureNetwork
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.network.ComponentSerializer
import dev.wildware.udea.network.EntityUpdate
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

object TransformSerializer : ComponentSerializer<Transform> {
    override fun serialize(component: Transform, entityUpdate: EntityUpdate) {
        with(entityUpdate) {
            byteBuffer.putFloat(component.position.x)
            byteBuffer.putFloat(component.position.y)
            byteBuffer.putFloat(component.rotation)
            byteBuffer.putFloat(component.scale.x)
            byteBuffer.putFloat(component.scale.y)
        }
    }

    override fun deserialize(component: Transform, entityUpdate: EntityUpdate) {
        with(entityUpdate) {
            component.position.x = byteBuffer.float
            component.position.y = byteBuffer.float
            component.rotation = byteBuffer.float
            component.scale.x = byteBuffer.float
            component.scale.y = byteBuffer.float
        }
    }
}
