package dev.wildware.udea.ecs.physics

import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.UdeaComponentType

/**
 * Component representing a capsule collision shape.
 * Requires a [Body] component to function properly.
 */
data class Capsule(
    /** The radius of the circle in world units */
    val radius: Float,

    /** The height of the circle in world units */
    val height: Float,
) : Component<Capsule> {
    /** @return The component type for this Circle component */
    override fun type() = Capsule

    companion object : UdeaComponentType<Capsule>(
        dependsOn = dependencies(Body)
    )
}
