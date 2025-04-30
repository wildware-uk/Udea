package dev.wildware.ability

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.AssetType
import dev.wildware.udea.assets.Assets

interface Ability {
    val name: String
    fun activate(world: World, abilityInfo: AbilityInfo)

    companion object : AssetType<Ability>() {
        override val id: String = "ability"
    }
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
    }.also { Assets[Ability][name] = it }
}
