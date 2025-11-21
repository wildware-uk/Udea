package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.component.animation.CharacterAnimationController
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.render.AnimationSet
import dev.wildware.udea.ecs.component.render.SpriteRenderer

class CharacterAnimationControllerSystem : IteratingSystem(
    family { all(CharacterAnimationController, AnimationSet, CharacterController, SpriteRenderer) }
) {
    override fun onTickEntity(entity: Entity) {
        val controller = entity[CharacterController]
        val animationSet = entity[AnimationSet]
        val animationController = entity[CharacterAnimationController]
        val animSetSystem = world.system<AnimationSetSystem>()
        val spriteRenderer = entity[SpriteRenderer]

        val movement = controller.movement

        if (!controller.castingAbility) {
            if (movement > 0F) {
                animSetSystem.setAnimation(entity, animationController.characterAnimations.run)
                spriteRenderer.flipX = false
            } else if (movement < 0F) {
                animSetSystem.setAnimation(entity, animationController.characterAnimations.run)
                spriteRenderer.flipX = true
            } else {
                animSetSystem.setAnimation(entity, animationController.characterAnimations.idle)
            }
        }
    }
}
