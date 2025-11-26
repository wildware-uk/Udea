package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.component.animation.Animations
import dev.wildware.udea.gameScreen

/**
 * Iterates through all entities with the [Animations] component,
 * updating their animations.
 * */
class AnimationSystem : IteratingSystem(
    family { all(Animations) }
) {
    override fun onTickEntity(entity: Entity) {
        val animations = entity[Animations]

        animations.animations.forEach {
            it.update(gameScreen.delta)
        }
    }

    fun onNotify(entity: Entity, name: String, onNotify: () -> Unit) {
        entity[Animations].animations.forEach {
            it.onNotify(name, onNotify)
        }
    }
}
