package dev.wildware.udea.ecs.component.physics

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.component.ComponentDependency.Companion.dependencies
import dev.wildware.udea.ecs.component.UdeaComponentType
import ktx.box2d.box

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
) : Component<Box> {
    override fun type() = Box

    override fun World.onAdd(entity: Entity) {
        entity[Body.Companion].body.box(width, height) {
            friction = this@Box.friction
        }
    }

    companion object : UdeaComponentType<Box>(
        dependsOn = dependencies(Body.Companion)
    )
}
