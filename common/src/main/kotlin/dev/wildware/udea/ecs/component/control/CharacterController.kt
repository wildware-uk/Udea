package dev.wildware.udea.ecs.component.control

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilityInfo

/**
 * Generic character controller for allowing players and AI to control
 * characters.
 * */
class CharacterController : Component<CharacterController> {

    /**
     * -1 is left, 1 is right, 0 is no movement.
     * */
    var movement = 0F

    /**
     * Is the character jumping?
     * */
    var jumping = false

    var castingAbility: Boolean = false

    val abilityQueue = mutableListOf<AbilitySpec>()

    fun activateAbility(spec: AbilitySpec) {
        abilityQueue += spec
    }

    override fun type() = CharacterController

    companion object : ComponentType<CharacterController>()
}

data class AbilitySpec(
    val ability: AbilityExec,
    val info: AbilityInfo
)

/**
 * Generic character controller class for player characters and NPCs.
 * */
interface ICharacterController {
    fun moveLeft()
    fun moveRight()
    fun moveUp()
    fun moveDown()
    fun attack(target: Entity)
}
