package dev.wildware.udea.example.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.Mouse
import dev.wildware.udea.Vector2
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.Axis2D
import dev.wildware.udea.assets.Control
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.render.Camera
import dev.wildware.udea.ecs.system.AbilitySystem
import dev.wildware.udea.ecs.system.ControllerSystem
import dev.wildware.udea.example.ability.Slot
import dev.wildware.udea.example.component.GameUnit
import dev.wildware.udea.example.component.Player
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.position

class PlayerControlSystem : IteratingSystem(
    family { all(Player, GameUnit, CharacterController) }
) {
    val gameUnitsFamily = family { all(Team, GameUnit) }

    val controls = world.system<ControllerSystem>()

    val movementAxis = Assets.get<Axis2D>("control/move")
    val attackControl = Assets.get<Control>("control/attack")
    val attack2Control = Assets.get<Control>("control/attack_2")

    override fun onTickEntity(entity: Entity) = context(world) {
        val controller = entity[CharacterController]
        val movementAxis = controls.getAxisValue(movementAxis)
        controller.movement.set(movementAxis)

        if (controls.isInputPressed(attackControl)) {
            val nearestEnemy = getAttackEntity(entity, entity.position.cpy().add(movementAxis))
            world.system<AbilitySystem>().activateAbilityByTag(
                AbilityInfo(entity, Mouse.mouseWorldPos, nearestEnemy),
                Slot.A
            )
        }

        if (controls.isInputPressed(attack2Control)) {
            val nearestEnemy = getAttackEntity(entity, entity.position.cpy().add(movementAxis))
            world.system<AbilitySystem>().activateAbilityByTag(
                AbilityInfo(entity, Mouse.mouseWorldPos, nearestEnemy),
                Slot.B
            )
        }

        if (Camera !in entity) {
            entity.configure {
                it += Camera()
            }
        }
    }

    context(world: World)
    private fun getAttackEntity(entity: Entity, vector: Vector2): Entity? {
        return gameUnitsFamily
            .filter { it[Team].teamId != entity[Team].teamId && !it[GameUnit].isDead }
            .filter { it.position.dst(vector) < 0.5F }
            .map { it }
            .minByOrNull { it.position.dst(vector) }
    }
}
