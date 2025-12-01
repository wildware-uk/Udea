package dev.wildware.udea.example.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.collection.EntityBag
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.ability.Attributes
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AbilitySystem
import dev.wildware.udea.example.ability.AIHint
import dev.wildware.udea.example.ability.AITag
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.component.AIUnit
import dev.wildware.udea.example.component.GameUnit
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.gameScreen
import dev.wildware.udea.position

/**
 * Iterates over all entities with a [Team] component and makes them attack.
 * */
class UnitAISystem : IteratingSystem(
    family { all(Team, CharacterController, Body, AIUnit) },
) {
    val gameUnitsFamily = family { all(Team, GameUnit) }
    var teamMembers = mapOf<Int, EntityBag>()

    override fun onTick() {
        if (!gameScreen.isServer) return

        teamMembers = gameUnitsFamily.groupBy { it[Team].teamId }
        super.onTick()
    }

    override fun onTickEntity(entity: Entity): Unit = context(world) {
        val team = entity[Team]
        val abilities = entity[Abilities]
        val attributes = entity[Attributes]
        val controller = entity[CharacterController]
        val gameUnit = entity[GameUnit]

        if (gameUnit.isDead) return@context

        val attributeSet = attributes.getAttributes<CharacterAttributeSet>()

        controller.movement.setZero()

        if (attributeSet.health.currentValue < attributeSet.maxHealth.currentValue / 2F) {
            val healingAbility = abilities.findAvailableAbilityWithTags(AIHint.Heal)

            if (healingAbility != null) {
                world.system<AbilitySystem>().activateAbility(entity, healingAbility)
            }
        }

        val nearestEnemy = gameUnitsFamily
            .filter { it[Team].teamId != team.teamId && !it[GameUnit].isDead && it.position.dst(entity.position) < 10F }
            .map { it }
            .minByOrNull { it.position.dst(entity.position) } ?: return

        val heading = nearestEnemy.position.cpy().sub(entity.position)
        val distance = heading.len()

        if(AITag.Fearless !in gameUnit.aiTags && attributeSet.health.currentValue <= 10) {
            controller.movement.set(heading.nor().scl(-1F))
            return@context
        }

        if (distance > .5F) {
            val rangedAbility = abilities.findAvailableAbilityWithTags(AIHint.Damage, AIHint.Ranged)

            if (rangedAbility != null) {
                world.system<AbilitySystem>().activateAbility(entity, rangedAbility)
            } else {
                controller.movement.set(heading.nor())
            }
        } else {
            val meleeAbility = abilities.findAvailableAbilityWithTags(AIHint.Damage, AIHint.Melee)

            if (meleeAbility != null) {
                world.system<AbilitySystem>().activateAbility(entity, meleeAbility)
            } else {
                controller.movement.set(heading.nor().scl(-1F))
            }
        }
    }
}
