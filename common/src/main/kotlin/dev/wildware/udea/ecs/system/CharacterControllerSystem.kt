package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.assets.MovementType.Sidescroller
import dev.wildware.udea.assets.MovementType.TopDown
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.gameScreen

class CharacterControllerSystem : IteratingSystem(
    family { all(CharacterController, Body, Abilities) }
) {
    val movementType = gameScreen.gameConfig.movementType

    override fun onTickEntity(entity: Entity) {
        if (!entity[CharacterController].isActive) return

        when (movementType) {
            TopDown -> moveTopDown(entity)
            Sidescroller -> moveSidescroller(entity)
        }
    }

    private fun moveTopDown(entity: Entity) {
        val characterController = entity[CharacterController]
        val body = entity[Body]

        if (characterController.movement.x != 0F) {
            val movement = characterController.movement.x.coerceIn(-1F, 1F) * characterController.moveSpeed
            body.body.applyLinearImpulse(movement, 0F, 0F, 0F, true)
        }

        if (characterController.movement.y != 0F) {
            val movement = characterController.movement.y.coerceIn(-1F, 1F) * characterController.moveSpeed
            body.body.applyLinearImpulse(0F, movement, 0F, 0F, true)
        }
    }

    private fun moveSidescroller(entity: Entity) {
        val characterController = entity[CharacterController]
        val body = entity[Body]

        val groundedPenalty = if (body.grounded) 1.0F else 0.2F
        val isJumping = characterController.movement.y > 0F

        if (characterController.movement.x != 0F) {
            val movement =
                characterController.movement.x.coerceIn(-1F, 1F) * groundedPenalty * characterController.moveSpeed
            body.body.applyLinearImpulse(movement, 0F, 0F, 0F, true)
        }

        if (isJumping && body.grounded) {
            body.body.applyLinearImpulse(0F, characterController.movement.y * 10F, 0F, 0F, true)
        }
    }
}
