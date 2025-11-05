package dev.wildware.udea.ecs.component.physics

import com.badlogic.gdx.math.Vector2
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

    /** The offset of the capsule */
    val offset: Vector2 = Vector2.Zero,
) : Component<Capsule>, PhysicsComponent {
    /** @return The component type for this Circle component */
    override fun type() = Capsule

    override fun registerComponent(body: Box2DBody) {
        with(body) {
            circle(width / 2.0F, Vector2(0F, height / 2).add(offset)) {
                density = 1.0F
            }

            box(width, height, offset) {
                density = 1.0F
            }

            circle(width / 2.0F, Vector2(0F, -height / 2F).add(offset)) {
                density = 1.0F
            }
        }
    }

    companion object : UdeaComponentType<Capsule>(
        dependsOn = dependencies(Body)
    )
}
