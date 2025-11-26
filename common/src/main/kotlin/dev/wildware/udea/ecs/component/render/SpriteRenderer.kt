package dev.wildware.udea.ecs.component.render

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Component
import dev.wildware.udea.assets.dsl.UdeaDsl
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.gameScreen
import ktx.assets.getAsset

class SpriteRenderer(
    var texture: TextureRegion? = null,
    var order: Int = 0,
    var flipX: Boolean = false,
    var flipY: Boolean = false,
    val offset: Vector2 = Vector2.Zero,
) : Component<SpriteRenderer> {
    override fun type() = SpriteRenderer

    companion object : UdeaComponentType<SpriteRenderer>()
}

private const val WORLD_SCALE = 0.1F

@UdeaDsl
fun loadSprite(path: String, scale: Float): Sprite {
    return Sprite(gameScreen.gameManager.assetManager.getAsset<Texture>(path)).apply {
        setSize(
            width * scale * WORLD_SCALE,
            height * scale * WORLD_SCALE
        )
    }
}
