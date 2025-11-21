package dev.wildware.udea.assets

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.graphics.g2d.TextureRegion
import dev.wildware.udea.dsl.CreateDsl
import dev.wildware.udea.ecs.component.UdeaClass
import dev.wildware.udea.ecs.component.UdeaClass.UClassAttribute.NoInline
import dev.wildware.udea.game
import kotlin.getValue
import kotlin.lazy

/**
 * An asset that represents a sprite.
 * */
@UdeaClass(NoInline)
data class Sprite(
    /**
     * The path to the sprite resource.
     * */
    val spritePath: String,
) : Asset()


@UdeaClass
data class SpriteSheet(
    val spritePath: String,
    val columns: Int,
    val rows: Int,
    val scale: Float = 1.0F,
) : Asset()

data class SpriteAnimationSet(
    val animations: List<SpriteAnimation>
) : Asset()

@CreateDsl
data class SpriteAnimation(
    val name: String,
    val sheet: AssetReference<SpriteSheet>,
    val loop: Boolean = true,
    val notifies: List<AnimNotify> = emptyList()
) {
    val spriteSheet by lazy {
        val sheetValue = sheet.value
        val spriteSheetTexture = game.gameManager.assetManager.get<Texture>(sheetValue.spritePath)
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
}

/**
 * Represents an event at a given frame that can be used to trigger other events.
 * */
@CreateDsl(onlyList = true)
data class AnimNotify(
    val frame: Int,
    val name: String
)
