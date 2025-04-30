package dev.wildware

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.physics.box2d.Fixture
import com.github.quillraven.fleks.Entity
import dev.wildware.ecs.system.Box2DSystem
import dev.wildware.spellcastgame.MainGame.Companion.camera
import ktx.box2d.query

object Mouse {

    val mouseWorldPos: Vector2
        get() {
            val mousePos = camera.unproject(Vector3(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0F))
            return Vector2(mousePos.x, mousePos.y)
        }

    val mouseTarget: Entity?
        get() {
            var fixture: Fixture? = null
            val mousePos = camera.unproject(Vector3(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0F))

            game.world.system<Box2DSystem>().box2DWorld
                .query(mousePos.x - .1F, mousePos.y - .1F, mousePos.x + .1F, mousePos.y + .1F) { f ->
                    fixture = f
                    false
                }

            return fixture?.body?.userData as? Entity
        }
}
