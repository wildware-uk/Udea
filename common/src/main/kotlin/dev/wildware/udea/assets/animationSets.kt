package dev.wildware.udea.assets

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.graphics.g2d.TextureRegion
import dev.wildware.udea.dsl.CreateDsl
import dev.wildware.udea.gameScreen

/**
 * Base interface for animation sets.
 * */
interface AnimationMap

data class SpriteAnimationSet(
    val animations: List<AssetReference<SpriteAnimation>>
) : Asset<SpriteAnimationSet>()

data class SpriteAnimation(
    val sheet: AssetReference<SpriteSheet>,
    val loop: Boolean = true,
    val interruptable: Boolean = true,
    val notifies: List<AnimNotify> = emptyList(),
    val frameTime: Float = 0.1F,
): Asset<SpriteAnimation>() {
    val spriteSheet by kotlin.lazy {
        val sheetValue = sheet.value
        val spriteSheetTexture = gameScreen.gameManager.assetManager.get<Texture>(sheetValue.spritePath)
        val frames = TextureRegion.split(
            spriteSheetTexture,
            spriteSheetTexture.width / sheetValue.columns,
            spriteSheetTexture.height / sheetValue.rows
        )

        buildList {
            frames.forEach {
                it.forEach {
                    add(Sprite(it).apply {
                        setSize(
                            it.regionWidth * sheetValue.scale,
                            it.regionHeight * sheetValue.scale
                        )
                    })
                }
            }
        }
    }

    val animation by kotlin.lazy {
        animation(
            name = this.name,
            loop = this.loop,
            frames = {
                var nextFrame = 0.0F

                spriteSheet.forEachIndexed { i, it ->

                    frame(
                        nextFrame,
                        it,
                        name = notifies.find { it.frame == i }?.name
                    )
                    nextFrame += frameTime
                }
            })
    }
}

/**
 * Represents an event at a given frame that can be used to trigger other events.
 * */
@CreateDsl(onlyList = true)
data class AnimNotify(
    val frame: Int,
    val name: String
)
