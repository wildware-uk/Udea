package dev.wildware.udea.ecs.render

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.component.UdeaComponentType

/**
 * Attaches a camera to an entity.
 * */
class Camera(
    val offset: Vector2 = Vector2.Zero
) : Component<Camera> {
    val position: Vector2 = Vector2()

    override fun type() = Camera

    companion object : UdeaComponentType<Camera>()
}
