package dev.wildware.systems

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.OrthographicCamera
import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.udea.InputSystem
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.gameScreen
import kotlin.math.pow

@UdeaSystem(runIn = [Editor])
class EditorSystem : IntervalSystem(), InputSystem {

    val scrollSpeed = 30F

    override fun onTick() {
        if (Gdx.input.isKeyPressed(Input.Keys.A)) {
            gameScreen.camera.translate(-scrollSpeed * deltaTime, 0F, 0F)
            gameScreen.camera.update()
        }

        if (Gdx.input.isKeyPressed(Input.Keys.D)) {
            gameScreen.camera.translate(scrollSpeed * deltaTime, 0F, 0F)
            gameScreen.camera.update()
        }

        if (Gdx.input.isKeyPressed(Input.Keys.W)) {
            gameScreen.camera.translate(0F, scrollSpeed * deltaTime, 0F)
            gameScreen.camera.update()
        }

        if (Gdx.input.isKeyPressed(Input.Keys.S)) {
            gameScreen.camera.translate(0F, -scrollSpeed * deltaTime, 0F)
            gameScreen.camera.update()
        }
    }

    override fun scrolled(amountX: Float, amountY: Float): Boolean {
        (gameScreen.camera as? OrthographicCamera)?.let {
            it.zoom *= 1.1F.pow(amountY)
            it.update()
        }

        return true
    }
}
