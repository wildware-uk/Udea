package dev.wildware.udea.ecs.system
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.ecs.component.Dead

class CleanupSystem : IteratingSystem(
    family { all(Dead) }
) {
    override fun onTickEntity(entity: Entity) {
        world -= entity
    }
}
