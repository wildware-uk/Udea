package dev.wildware.udea.example.system

import com.badlogic.gdx.math.Vector2.Zero
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.collection.EntityBag
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AbilitySystem
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
        val controller = entity[CharacterController]
        val gameUnit = entity[GameUnit]

        if (gameUnit.isDead) return@context

        controller.movement.setZero()

        val nearestEnemy = gameUnitsFamily
            .filter { it[Team].teamId != team.teamId && !it[GameUnit].isDead }
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
