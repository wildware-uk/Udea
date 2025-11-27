package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.assets.CharacterAnimationMap
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.animation.AnimationMapHolder
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.render.AnimationHolder
import dev.wildware.udea.ecs.component.render.SpriteRenderer

class CharacterAnimationControllerSystem : IteratingSystem(
    family { all(AnimationMapHolder, AnimationHolder, CharacterController, SpriteRenderer) }
) {
    override fun onTickEntity(entity: Entity) {
        val controller = entity[CharacterController]
        if (!controller.isActive) return

        val animationHolder = entity[AnimationHolder]
        val animationMapHolder = entity[AnimationMapHolder]
        val animSetSystem = world.system<AnimationSetSystem>()
        val spriteRenderer = entity[SpriteRenderer]
        val abilities = entity[Abilities]

        val movement = controller.movement

        val characterAnimations = animationMapHolder.animationMap as? CharacterAnimationMap ?: return

        if (abilities.currentAbility == null) {
            val moving = !movement.isZero

            if (moving) {
                animSetSystem.setAnimation(entity, characterAnimations.run)
            } else {
                animSetSystem.setAnimation(entity, characterAnimations.idle)
            }

            if (movement.x > 0F) {
                spriteRenderer.flipX = false
            } else if (movement.x < 0F) {
                spriteRenderer.flipX = true
            }
        }
    }
}
