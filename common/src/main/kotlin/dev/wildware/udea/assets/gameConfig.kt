package dev.wildware.udea.assets

import com.badlogic.gdx.Gdx
import dev.wildware.udea.Vector2

data class GameConfig(
    val defaultLevel: AssetReference<Level>? = null,
    val defaultCharacter: AssetReference<Blueprint>? = null,
    val backgroundTexture: String? = null,
    val lighting: Lighting? = null,
    val network: Network = Network(),
    val scene2d: Scene2D? = null,
    val physics: Physics = Physics(),
    val movementType: MovementType = MovementType.TopDown
) : Asset<GameConfig>()

data class Scene2D(
    val scene2DDefaultSkin: String? = null
): Asset<Scene2D>()

data class Network(
    val tcpPort: Int = 28855,
    val udpPort: Int = 28856,
): Asset<Network>()

data class Lighting(
    val shadows: Boolean = true,
    val ambientLight: Float = 0.5F,
    val blurNum: Int = 3,
    val blur: Boolean = true,
    val fboWidth: Int = Gdx.graphics.width,
    val fboHeight: Int = Gdx.graphics.height,
): Asset<Lighting>()

data class Physics(
    val gravity: Vector2 = Vector2(0F, -9.81F)
): Asset<Physics>()

enum class MovementType {
    TopDown, Sidescroller
}
