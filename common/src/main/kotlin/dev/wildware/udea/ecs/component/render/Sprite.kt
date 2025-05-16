package dev.wildware.udea.ecs.component.render

import com.badlogic.gdx.graphics.Texture
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.game
import ktx.assets.getAsset
import dev.wildware.udea.assets.Sprite as SpriteAsset
import com.badlogic.gdx.graphics.g2d.Sprite as GdxSprite

class Sprite(
    var sprite: SpriteAsset,
    var order: Int = 0
) : Component<Sprite> {
    lateinit var gdxSprite: GdxSprite

    override fun type() = Sprite

    override fun World.onAdd(entity: Entity) {
        val tex = game.assetManager.getAsset<Texture>(entity[Sprite].sprite.spritePath)
        gdxSprite = GdxSprite(tex)
    }

    companion object : UdeaComponentType<Sprite>()
}
