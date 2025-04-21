package dev.wildware.udea.ecs.physics

import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.UdeaComponentType

/**
 * Represents a rectangular box component that defines the physical dimensions of an entity.
 */
data class Box(
    /** The width of the box in world units */
    val width: Float,
    /** The height of the box in world units */
    val height: Float,
) : Component<Box> {
    override fun type() = Box

    companion object : UdeaComponentType<Box>(
        dependsOn = dependencies(Body)
    )
}
