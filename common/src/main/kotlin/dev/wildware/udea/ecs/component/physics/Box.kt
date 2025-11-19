package dev.wildware.udea.ecs.component.physics

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import dev.wildware.udea.Vector2
import dev.wildware.udea.ecs.component.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.component.UdeaComponentType
import ktx.box2d.box
import com.badlogic.gdx.physics.box2d.Body as Box2DBody

/**
 * Represents a rectangular box component that defines the physical dimensions of an entity.
 */
data class Box(
    /** The width of the box in world units */
    val width: Float = 1.0F,
    /** The height of the box in world units */
    val height: Float = 1.0F,
    /** The friction of the box */
    val friction: Float = 0.0F,
    /** The offset of the box */
    val offset: Vector2 = Vector2.Zero,

    override val isSensor: Boolean = false
) : Component<Box>, PhysicsComponent {
    override fun type() = Box

    override fun registerComponent(entity: Entity, body: Box2DBody) {
        body.box(width, height, offset) {
            friction = this@Box.friction
            density = 1.0F
            isSensor = this@Box.isSensor
            userData = entity
        }
    }

    companion object : UdeaComponentType<Box>(
        dependsOn = dependencies(Body)
    )
}
