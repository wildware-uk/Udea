package dev.wildware.udea.assets

import dev.wildware.udea.ecs.component.UdeaClass
import dev.wildware.udea.ecs.component.UdeaClass.UClassAttribute.NoInline

/**
 * An asset that represents a sprite.
 * */
@UdeaClass(NoInline)
data class Sprite(
    /**
     * The path to the sprite resource.
     * */
    val spritePath: String,
) : Asset<Sprite>()


@UdeaClass
data class SpriteSheet(
    val spritePath: String,
    val columns: Int,
    val rows: Int,
    val scale: Float = 1.0F,
) : Asset<SpriteSheet>()
