package dev.wildware.udea.ecs.system

import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.graphics.OrthographicCamera
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.InputSystem
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.render.Camera
import dev.wildware.udea.gameScreen
import dev.wildware.udea.hasAuthority
import kotlin.math.pow

class CameraTrackSystem : IteratingSystem(
    family { all(Transform, Camera) }
), InputSystem {
    override fun onTickEntity(entity: Entity) {
        if (world.hasAuthority(entity)) {
            val camera = gameScreen.camera

            val position = entity[Camera].position
            position.set(
                entity[Transform].position.x + entity[Camera].offset.x,
                entity[Transform].position.y + entity[Camera].offset.y
            )

            camera.position.set(position, 0F)
            camera.update()
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
