package dev.wildware.udea.ecs.system

import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.World.Companion.inject
import com.github.quillraven.fleks.collection.compareEntity
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.game
import dev.wildware.udea.use
import dev.wildware.udea.ecs.component.render.SpriteRenderer as SpriteComponent

@UdeaSystem(runIn = [Editor, Game])
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

        val spriteComponent = entity[SpriteComponent]
        val texture = spriteComponent.texture ?: return

        val scaleX = if(spriteComponent.flipX) -transform.scale.x else transform.scale.x
        val scaleY = if(spriteComponent.flipY) -transform.scale.y else transform.scale.y

        val posX = position.x + spriteComponent.offset.x
        val posY = position.y + spriteComponent.offset.y

        if (texture is Sprite) {
            spriteBatch.draw(
                texture,
                posX - texture.width / 2,
                posY - texture.height / 2,
                texture.width / 2,
                texture.height / 2,
                texture.width,
                texture.height,
                scaleX,
                scaleY,
                Math.toDegrees(transform.rotation.toDouble()).toFloat()
            )
        } else {
            spriteBatch.draw(
                texture,
                posX - texture.regionWidth / 2,
                posY - texture.regionHeight / 2,
                texture.regionWidth / 2F,
                texture.regionHeight / 2F,
                texture.regionWidth.toFloat(),
                texture.regionHeight.toFloat(),
                scaleX,
                scaleY,
                Math.toDegrees(transform.rotation.toDouble()).toFloat()
            )
        }
    }
}
