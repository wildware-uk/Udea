package dev.wildware.udea.ecs.system

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.gameScreen
import dev.wildware.udea.use

@UdeaSystem(runIn = [Editor, Game])
class BackgroundDrawSystem : IntervalSystem() {

    val background by lazy {
        gameScreen.gameConfig.backgroundTexture?.let {
            gameScreen.gameManager.assetManager.get<Texture>(
                it
            )
        }
    }
    val spriteBatch = SpriteBatch()

    override fun onTick() {
        if (background == null) return

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
