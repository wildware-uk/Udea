package dev.wildware.udea.ecs.physics

import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.UdeaComponentType

/**
 * Component representing a circular collision shape.
 * Requires a [Body] component to function properly.
 */
data class Circle(
    /** The radius of the circle in world units */
    val radius: Float,
) : Component<Circle> {
    /** @return The component type for this Circle component */
    override fun type() = Circle

    companion object : UdeaComponentType<Circle>(
        dependsOn = dependencies(Body)
    )
}
