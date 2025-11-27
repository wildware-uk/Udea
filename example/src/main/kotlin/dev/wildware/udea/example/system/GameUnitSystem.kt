package dev.wildware.udea.example.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.animation.AnimationMapHolder
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.ability.Debuffs
import dev.wildware.udea.example.character.GameUnitAnimationMap
import dev.wildware.udea.example.component.GameUnit

class GameUnitSystem : IteratingSystem(
    family { all(GameUnit, Abilities, CharacterController) }
) {
    override fun onTickEntity(entity: Entity) {
        val abilities = entity[Abilities]
        val gameUnit = entity[GameUnit]
        val controller = entity[CharacterController]

        val attributes = abilities.attributeSet as CharacterAttributeSet

        checkDead(gameUnit, attributes, entity, controller)

        if (gameUnit.isDead) return

        controller.isActive = !abilities.hasGameplayEffectTag(Debuffs.Stunned)
    }

    private fun checkDead(
        gameUnit: GameUnit,
        attributes: CharacterAttributeSet,
        entity: Entity,
        controller: CharacterController
    ) {
        if (!gameUnit.isDead && attributes.health.currentValue <= 0F) {
            entity[Body].body.fixtureList.forEach {
                it.isSensor = true
            }

            controller.isActive = false

            world.system<AnimationSetSystem>()
                .setAnimation(
                    entity,
                    entity[AnimationMapHolder].animationMap<GameUnitAnimationMap>().death,
                    force = true
                )

            gameUnit.isDead = true
        }
    }
}
