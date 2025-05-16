package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.render.Camera
import dev.wildware.udea.game
import dev.wildware.udea.hasAuthority

class CameraTrackSystem : IteratingSystem(
    family { all(Transform, Camera, Networkable) }
) {
    override fun onTickEntity(entity: Entity) {
        if (world.hasAuthority(entity)) {
            val camera = game.camera ?: return

            val position = entity[Camera].position
            position.set(
                entity[Transform].position.x + entity[Camera].offset.x,
                entity[Transform].position.y + entity[Camera].offset.y
            )

            camera.position.set(position, 0F)
            camera.update()
        }
    }
}
