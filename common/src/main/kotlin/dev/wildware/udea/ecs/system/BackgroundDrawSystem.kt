package dev.wildware.udea.ecs.system

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.game
import dev.wildware.udea.use

@UdeaSystem(runIn = [Editor, Game])
class BackgroundDrawSystem : IntervalSystem() {

    val gameConfig by lazy { Assets.filterIsInstance<GameConfig>().first() }
    val background by lazy { game.gameManager.assetManager.get<Texture>(gameConfig.backgroundTexture) }
    val spriteBatch = SpriteBatch()

    override fun onTick() {
        spriteBatch.use { batch ->
            batch.draw(
                background,
                0F,
                0F,
                Gdx.graphics.width.toFloat(),
                Gdx.graphics.height.toFloat(),
            )
        }
    }
}
