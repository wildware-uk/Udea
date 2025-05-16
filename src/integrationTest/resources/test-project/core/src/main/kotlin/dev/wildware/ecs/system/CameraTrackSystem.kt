package dev.wildware.udea.ecs.system
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.ecs.component.CameraTrack
import dev.wildware.ecs.component.Networkable
import dev.wildware.ecs.component.Transform
import dev.wildware.hasAuthority
import dev.wildware.spellcastgame.MainGame.Companion.camera

class CameraTrackSystem : IteratingSystem(
    family { all(Transform, CameraTrack, Networkable) }
) {
    override fun onTickEntity(entity: Entity) {
        if (world.hasAuthority(entity)) {
            val position = entity[CameraTrack].position
            position.set(
                entity[Transform].position.x + entity[CameraTrack].offset.x,
                entity[Transform].position.y + entity[CameraTrack].offset.y
            )
            camera.position.set(position, 0F)
            camera.update()
        }
    }
}
