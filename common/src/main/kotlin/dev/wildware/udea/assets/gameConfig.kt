package dev.wildware.udea.assets

import com.badlogic.gdx.Gdx

// TODO implement these settings

data class GameConfig(
    val defaultLevel: AssetReference<Level>? = null,
    val defaultCharacter: AssetReference<Blueprint>? = null,
    val backgroundTexture: String? = null,
    val lighting: Lighting? = null,
) : Asset()

data class Lighting(
    val shadows: Boolean = true,
    val ambientLight: Float = 0.5F,
    val blurNum: Int = 3,
    val blur: Boolean = true,
    val fboWidth: Int = Gdx.graphics.width,
    val fboHeight: Int = Gdx.graphics.height,
): Asset()
