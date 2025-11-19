package dev.wildware.udea.ability

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World

/**
 * The actual execution of an ability.
 * */
interface AbilityExec {
    context(world: World)
    fun activate(abilityInfo: AbilityInfo)
}

/**
 * Data about an ability cast.
 * */
data class AbilityInfo(
    val source: Entity,
    val targetPos: Vector2,
    val target: Entity?
)
