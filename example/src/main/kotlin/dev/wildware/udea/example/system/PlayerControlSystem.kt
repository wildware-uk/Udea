package dev.wildware.udea.example.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.Vector2
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
import dev.wildware.udea.example.getUnitsWithin
import dev.wildware.udea.hasAuthority
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
        // `with(world)` wraps this whole body (issue #186). Kotlin 2.4 will not choose between
        // the system's own `EntityComponentContext` and the `context(world)` this body already
        // declares, and reports every `entity[...]`, `in` and `configure` call as ambiguous. For a
        // Fleks system those two candidates are the same `World` object, so naming one changes
        // nothing; `with` is the spelling the compiler itself suggests. The `context(world)` stays
        // because some calls in here -- `Entity.position`, `applyGameplayEffectToSelf` -- want
        // `World` as a context *parameter*, which an implicit receiver does not supply.
        // Diffing this file with whitespace ignored shows only these added lines.
        with(world) {
                if(!world.hasAuthority(entity)) return@context

                val controller = entity[CharacterController]
                val movementAxis = controls.getAxisValue(movementAxis)
                controller.movement.set(movementAxis)

                if (controls.isInputPressed(attackControl)) {
        //            val nearestEnemy = getAttackEntity(entity, entity.position.cpy().add(movementAxis))
                    world.system<AbilitySystem>().activateAbilityByTag(entity, Slot.A)
                }

                if (controls.isInputPressed(attack2Control)) {
        //            val nearestEnemy = getAttackEntity(entity, entity.position.cpy().add(movementAxis))
                    world.system<AbilitySystem>().activateAbilityByTag(entity, Slot.B)
                }

                if (Camera !in entity) {
                    entity.configure {
                        it += Camera()
                    }
                }
        }
    }

    context(world: World)
    private fun getAttackEntity(entity: Entity, vector: Vector2): Entity? {
        return getUnitsWithin(entity, 1.0F)
            // `with(world)` for the reason given on `onTickEntity` above: the context parameter
            // and this system's own `EntityComponentContext` are the same `World`, and Kotlin 2.4
            // asks which one is meant. `it.position` outside the `with` still reads the context
            // parameter, which is where it has always come from.
            .filter { with(world) { it[Team].teamId != entity[Team].teamId && !it[GameUnit].isDead } }
            .minByOrNull { it.position.dst(vector) }
    }
}
