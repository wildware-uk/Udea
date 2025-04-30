package dev.wildware.ecs.system

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.game
import dev.wildware.spellcastgame.MainGame.Companion.viewPort
import ktx.graphics.use

class BackgroundDrawSystem : IntervalSystem() {

    val background by lazy { game.assetManager.get<Texture>("background.png") }
    val spriteBatch = SpriteBatch()

    override fun onTick() {
        spriteBatch.use { batch ->
            batch.draw(
                background,
                0F,
                0F,
                viewPort.screenWidth.toFloat(),
                viewPort.screenHeight.toFloat(),
            )
        }
    }
}
