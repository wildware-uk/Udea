package dev.wildware.udea.ability

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.Ability


/**
 * The actual execution of an ability.
 * */
abstract class AbilityExec(
    val abilityActivation: AbilityActivation
) {
    context(world: World)
    abstract fun activate(abilityInfo: AbilityInfo)

    fun endAbility() {
        abilityActivation.abilityFinished = true
    }
}

data class AbilityActivation(
    val ability: Ability,
    val info: AbilityInfo
) {
    private var onFinish: ((AbilityActivation) -> Unit)? = null

    var abilityFinished = false
        set(value) {
            field = value
            if(value) {
                onFinish?.invoke(this)
            }
        }

    fun onFinish(onFinish: (AbilityActivation) -> Unit) {
        this.onFinish = onFinish
    }
}

/**
 * Data about an ability cast.
 * */
data class AbilityInfo(
    val source: Entity,
    val targetPos: Vector2,
    val target: Entity?
)
