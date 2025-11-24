package dev.wildware.systems

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.OrthographicCamera
import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.udea.InputSystem
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.game
import kotlin.math.pow

@UdeaSystem(runIn = [Editor])
class EditorSystem : IntervalSystem(), InputSystem {

    val scrollSpeed = 30F

    override fun onTick() {
        if (Gdx.input.isKeyPressed(Input.Keys.A)) {
            game.camera.translate(-scrollSpeed * deltaTime, 0F, 0F)
            game.camera.update()
        }

        if (Gdx.input.isKeyPressed(Input.Keys.D)) {
            game.camera.translate(scrollSpeed * deltaTime, 0F, 0F)
            game.camera.update()
        }

        if (Gdx.input.isKeyPressed(Input.Keys.W)) {
            game.camera.translate(0F, scrollSpeed * deltaTime, 0F)
            game.camera.update()
        }

        if (Gdx.input.isKeyPressed(Input.Keys.S)) {
            game.camera.translate(0F, -scrollSpeed * deltaTime, 0F)
            game.camera.update()
        }
    }

    override fun scrolled(amountX: Float, amountY: Float): Boolean {
        (game.camera as? OrthographicCamera)?.let {
            it.zoom *= 1.1F.pow(amountY)
            it.update()
        }

        return true
    }
}
