package dev.wildware.ability

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.AssetType
import dev.wildware.udea.assets.Assets

interface Ability {
    val name: String
    fun activate(world: World, abilityInfo: dev.wildware.udea.ability.AbilityInfo)

    companion object : AssetType<dev.wildware.udea.ability.Ability>() {
        override val id: String = "ability"
    }
}

data class AbilityInfo(
    val source: Entity,
    val targetPos: Vector2,
    val target: Entity?
)

fun ability(name: String, activate: World.(dev.wildware.udea.ability.AbilityInfo) -> Unit): dev.wildware.udea.ability.Ability {
    return object : dev.wildware.udea.ability.Ability {
        override val name: String = name

        override fun activate(world: World, abilityInfo: dev.wildware.udea.ability.AbilityInfo) {
            activate(world, abilityInfo)
        }
    }.also { Assets[Ability][name] = it }
}
