package dev.wildware.systems

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.InputSystem
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.game
import ktx.graphics.use
import kotlin.math.pow

@UdeaSystem(runIn = [Editor])
class EditorSystem : IntervalSystem(), InputSystem {

     override fun onTick() {
        if(Gdx.input.isKeyPressed(Input.Keys.A)) {
            game.camera.translate(-10F * deltaTime, 0F, 0F)
            game.camera.update()
        }

        if(Gdx.input.isKeyPressed(Input.Keys.D)) {
            game.camera.translate(10F * deltaTime, 0F, 0F)
            game.camera.update()
        }

        if(Gdx.input.isKeyPressed(Input.Keys.W)) {
            game.camera.translate(0F, 10F * deltaTime, 0F)
            game.camera.update()
        }

        if(Gdx.input.isKeyPressed(Input.Keys.S)) {
            game.camera.translate(0F, -10F * deltaTime, 0F)
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
