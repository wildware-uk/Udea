package dev.wildware.udea.ecs.physics

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.component.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.component.UdeaComponentType
import ktx.box2d.circle

/**
 * Component representing a circular collision shape.
 * Requires a [Body] component to function properly.
 */
data class Circle(
    /** The radius of the circle in world units */
    val radius: Float = 1.0F,

    /** The friction of the circle */
    val friction: Float = 0.0F,
) : Component<Circle> {
    /** @return The component type for this Circle component */
    override fun type() = Circle

    override fun World.onAdd(entity: Entity) {
        entity[Body].body.circle(radius) {
            friction = this@Circle.friction
        }
    }

    companion object : UdeaComponentType<Circle>(
        dependsOn = dependencies(Body)
    )
}
