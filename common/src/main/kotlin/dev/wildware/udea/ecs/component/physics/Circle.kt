package dev.wildware.udea.ecs.component.physics

import com.badlogic.gdx.physics.box2d.Body
import com.github.quillraven.fleks.Component
import dev.wildware.udea.Vector2
import dev.wildware.udea.ecs.component.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.component.UdeaComponentType
import ktx.box2d.circle
import com.badlogic.gdx.physics.box2d.Body as Box2DBody

/**
 * Component representing a circular collision shape.
 * Requires a [Body] component to function properly.
 */
data class Circle(
    /** The radius of the circle in world units */
    val radius: Float = 1.0F,

    /** The friction of the circle */
    val friction: Float = 0.0F,

    /** The offset of the circle */
    val offset: Vector2 = Vector2.Zero
) : Component<Circle>, PhysicsComponent {
    /** @return The component type for this Circle component */
    override fun type() = Circle

    override fun registerComponent(body: Box2DBody) {
        body.circle(radius, offset) {
            friction = this@Circle.friction
        }
    }

    companion object : UdeaComponentType<Circle>(
        dependsOn = dependencies(Body)
    )
}
