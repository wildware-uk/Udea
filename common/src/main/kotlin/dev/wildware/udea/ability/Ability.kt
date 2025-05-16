package dev.wildware.udea.ability

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World

interface Ability {
    val name: String
    fun activate(world: World, abilityInfo: AbilityInfo)
}

data class AbilityInfo(
    val source: Entity,
    val targetPos: Vector2,
    val target: Entity?
)

fun ability(name: String, activate: World.(AbilityInfo) -> Unit): Ability {
    return object : Ability {
        override val name: String = name

        override fun activate(world: World, abilityInfo: AbilityInfo) {
            activate(world, abilityInfo)
        }
    }
}
