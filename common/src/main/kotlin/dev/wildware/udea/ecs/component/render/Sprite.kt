package dev.wildware.udea.ecs.component.render

import com.badlogic.gdx.graphics.Texture
import com.fasterxml.jackson.annotation.JsonIgnore
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.game
import ktx.assets.getAsset
import com.badlogic.gdx.graphics.g2d.Sprite as GdxSprite

class Sprite(
    val sprite: String,
    var order: Int = 0,
    val scale: Float = 1.0F,
) : Component<Sprite> {
    @JsonIgnore
    var gdxSprite: GdxSprite? = null

    override fun type() = Sprite

    override fun World.onAdd(entity: Entity) {
        val tex = game.gameManager.assetManager.getAsset<Texture>(sprite)

        gdxSprite = GdxSprite(tex).apply {
            setSize(
                width * scale * WORLD_SCALE,
                height * scale * WORLD_SCALE
            )
        }
    }

    companion object : UdeaComponentType<Sprite>() {
        private const val WORLD_SCALE = 0.1F
    }
}
