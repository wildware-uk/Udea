package dev.wildware.ecs.component

import com.badlogic.gdx.graphics.g2d.Sprite
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

class RenderSprite(
    val sprite: Sprite,
    val order: Int = 0
) : Component<RenderSprite> {
    override fun type()= RenderSprite
    override fun toString(): String {
        return "RenderSprite(sprite=$sprite)"
    }
    companion object : ComponentType<RenderSprite>()
}
