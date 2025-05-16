package dev.wildware.udea.ecs.system

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import com.github.quillraven.fleks.collection.compareEntity
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.render.Sprite as SpriteComponent
import dev.wildware.udea.game
import dev.wildware.udea.use
import ktx.assets.getAsset

class SpriteBatchSystem(
    val spriteBatch: SpriteBatch = inject()
) : IteratingSystem(
    family { all(Transform, SpriteComponent) },
    compareEntity { entA, entB -> entA[SpriteComponent].order.compareTo(entB[SpriteComponent].order) }
) {

    override fun onTick() {
        spriteBatch.use(game.camera) {
            super.onTick()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        val position = transform.position

        val tex = game.assetManager.getAsset<Texture>(entity[SpriteComponent].sprite.spritePath)
        val texture = Sprite(tex)

        spriteBatch.draw(
            texture,
            position.x - texture.width / 2,
            position.y - texture.height / 2,
            texture.width / 2,
            texture.height / 2,
            texture.width,
            texture.height,
            transform.scale.x,
            transform.scale.y,
            Math.toDegrees(transform.rotation.toDouble()).toFloat()
        )
    }
}
