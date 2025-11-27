package dev.wildware.udea.example.system

import com.badlogic.gdx.math.Vector2.Zero
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.collection.EntityBag
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.animation.AnimationMapHolder
import dev.wildware.udea.ecs.component.base.Debug
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AbilitySystem
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.ability.Debuffs
import dev.wildware.udea.example.character.NPCAnimationMap
import dev.wildware.udea.example.component.NPC
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.gameScreen
import dev.wildware.udea.position

/**
 * Iterates over all entities with a [Team] component and makes them attack.
 * */
class UnitAISystem : IteratingSystem(
    family { all(Team, CharacterController, Body) },
) {
    var teamMembers = mapOf<Int, EntityBag>()

    override fun onTick() {
        if (!gameScreen.isServer) return

        teamMembers = family.groupBy { it[Team].teamId }
        super.onTick()
    }

    override fun onTickEntity(entity: Entity): Unit = context(world) {
        val team = entity[Team]
        val controller = entity[CharacterController]
        val abilities = entity[Abilities]
        val npc = entity[NPC]
        val attributes = abilities.attributeSet as CharacterAttributeSet

        if (!npc.isDead && attributes.health.currentValue <= 0F) {
            entity[Body].body.fixtureList.forEach {
                it.isSensor = true
            }
            controller.isActive = false
            world.system<AnimationSetSystem>().setAnimation(entity, entity[AnimationMapHolder].animationMap<NPCAnimationMap>().death, force = true)
            npc.isDead = true
        }

        if (npc.isDead) return@context

        entity[Debug].addMessage(abilities.currentAbility?.ability?.name ?: "No Ability")

        controller.movement.setZero()
        if (abilities.hasGameplayEffectTag(Debuffs.Stunned)) {
            return
        }

        if (entity.position.dst(Zero) > 3F) {
            controller.movement.set(Zero.cpy().sub(entity.position).nor())
            return
        }

        val nearestEnemy = family
            .filter { it[Team].teamId != team.teamId && !it[NPC].isDead }
            .map { it }
            .minByOrNull { it.position.dst(entity.position) } ?: return

        val heading = nearestEnemy.position.cpy().sub(entity.position)
        val distance = heading.len()

        if (distance > .5F) {
            controller.movement.set(heading.nor())
        } else {
            world.system<AbilitySystem>().activateAbility(
                AbilityInfo(entity, Zero, nearestEnemy),
                Assets["ability/npc_melee"]
            )

        }
    }
}
