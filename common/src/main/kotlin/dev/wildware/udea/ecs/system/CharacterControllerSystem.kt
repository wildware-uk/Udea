package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.physics.Body

class CharacterControllerSystem : IteratingSystem(
    family { all(CharacterController, Body, Abilities) }
) {
    override fun onTickEntity(entity: Entity) {
        val characterController = entity[CharacterController]
        val body = entity[Body]

        val groundedPenalty = if (body.grounded) 1.0F else 0.2F

        if (characterController.movement != 0F) {
            val movement = characterController.movement.coerceIn(-1F, 1F) * groundedPenalty
            body.body.applyLinearImpulse(movement, 0F, 0F, 0F, true)
        }

        val ability = characterController.abilityQueue.removeFirstOrNull()

        if (ability != null) {
            world.system<AbilitySystem>().activateAbility(ability.info, ability.ability.value)
        }
    }
}
