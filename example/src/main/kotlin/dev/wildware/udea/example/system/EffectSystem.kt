package dev.wildware.udea.example.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.component.base.Dead
import dev.wildware.udea.ecs.component.render.AnimationHolder
import dev.wildware.udea.example.component.Effect
import dev.wildware.udea.gameScreen

class EffectSystem : IteratingSystem(
    family { all(Effect, AnimationHolder) }
) {
    override fun onTickEntity(entity: Entity) {
        if (gameScreen.time >= entity[Effect].destroyTime) {
            entity.configure {
                it += Dead
            }
        }
    }
}
