package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.component.base.Transform

class TransformSystem : IteratingSystem(
    family { all(Transform) }
) {
    override fun onTickEntity(entity: Entity) {
        val transform = entity[Transform]
        transform.parent?.let {
            transform.position.set(it.position)
            transform.rotation = it.rotation
            transform.scale.set(it.scale)
        }
    }
}