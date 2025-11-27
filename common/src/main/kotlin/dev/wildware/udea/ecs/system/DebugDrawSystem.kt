package dev.wildware.udea.ecs.system

import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector3
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.kotcrab.vis.ui.VisUI
import dev.wildware.udea.ecs.UdeaSystem
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Editor
import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game
import dev.wildware.udea.ecs.component.base.Debug
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.gameScreen
import ktx.graphics.use

@UdeaSystem(runIn = [Editor, Game])
class DebugDrawSystem : IteratingSystem(
    family { all(Transform, Debug) },
) {
    val font = VisUI.getSkin().getFont("default-font")
    val spriteBatch = SpriteBatch()

    override fun onTick() {
        spriteBatch.use {
            super.onTick()
            font.draw(it, if (gameScreen.isServer) "server" else "client", 10F, 50F)
        }
    }

    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        val debug = entity[Debug]

        if (gameScreen.debug) {
            val position = gameScreen.camera.project(Vector3(transform.position, 0f))

            font.setColor(1.0F, 1.0F, 1.0F, 1.0F)
            debug.debugMessages.forEachIndexed { i, it ->
                font.draw(spriteBatch, it.message, position.x + 10F, position.y + i * 25F)
            }
        }

        debug.debugMessages.removeIf {
            gameScreen.time >= it.destroyTime
        }
    }
}
