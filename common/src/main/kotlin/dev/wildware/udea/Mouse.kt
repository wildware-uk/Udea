package dev.wildware.udea

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.physics.box2d.Fixture
import com.github.quillraven.fleks.Entity
import dev.wildware.udea.ecs.system.Box2DSystem
import ktx.box2d.query

object Mouse {

    val mouseWorldPos: Vector2
        get() {
            val mousePos = game.camera.unproject(Vector3(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0F))
            return Vector2(mousePos.x, mousePos.y)
        }

    val mouseTarget: Entity?
        get() {
            var fixture: Fixture? = null
            val mousePos = game.camera.unproject(Vector3(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0F))

            game.world.system<Box2DSystem>().box2DWorld
                .query(mousePos.x - .1F, mousePos.y - .1F, mousePos.x + .1F, mousePos.y + .1F) { f ->
                    fixture = f
                    false
                }

            return fixture?.body?.userData as? Entity
        }
}