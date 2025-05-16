package dev.wildware.udea.ecs.system
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.udea.game
import ktx.graphics.use

class BackgroundDrawSystem : IntervalSystem() {

    val background by lazy { game.assetManager.get<Texture>("background.png") }
    val spriteBatch = SpriteBatch()

    override fun onTick() {
//        spriteBatch.use { batch -> TODO add as level config
//            batch.draw(
//                background,
//                0F,
//                0F,
//                viewPort.screenWidth.toFloat(),
//                viewPort.screenHeight.toFloat(),
//            )
        }
//    }
}
