package dev.wildware.udea.ecs.component.physics

import com.badlogic.gdx.physics.box2d.Body
import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.component.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.component.UdeaComponentType
import ktx.box2d.box
import ktx.box2d.circle
import com.badlogic.gdx.physics.box2d.Body as Box2DBody

/**
 * Component representing a capsule collision shape.
 * Requires a [Body] component to function properly.
 */
data class Capsule(
    /** The width of the capsule */
    val width: Float = 1.0F,

    /** The height of the capsule in world units */
    val height: Float = 2.0F,
) : Component<Capsule>, PhysicsComponent {
    /** @return The component type for this Circle component */
    override fun type() = Capsule

    override fun registerComponent(body: Box2DBody) {
        with(body) {
            circle(width / 2.0F)
            box(width, height)
            circle(width / 2.0F)
        }
    }

    companion object : UdeaComponentType<Capsule>(
        dependsOn = dependencies(Body)
    )
}
