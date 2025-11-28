package dev.wildware.udea.ability

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.Ability
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.get


/**
 * The actual execution of an ability.
 * */
abstract class AbilityExec {
    /**
     * Activate this ability.
     * */
    context(world: World, activation: AbilityActivation)
    abstract fun activate(abilityInfo: AbilityInfo)

    /**
     * Commit the cooldown/costs, marks the ability as active.
     * */
    context(world: World, activation: AbilityActivation)
    fun commitAbility() {
        activation.commit()
        activation.active = true
    }

    /**
     * Ends the ability.
     * */
    context(activation: AbilityActivation)
    fun endAbility() {
        activation.active = false
        activation.abilityFinished = true
    }
}


data class AbilityActivation(
    val ability: Ability,
    val info: AbilityInfo
) {
    private var onFinish: ((AbilityActivation) -> Unit)? = null

    var active: Boolean = false

    var abilityFinished = false
        set(value) {
            field = value
            if (value) {
                onFinish?.invoke(this)
            }
        }

    fun onFinish(onFinish: (AbilityActivation) -> Unit) {
        this.onFinish = onFinish
    }

    context(world: World)
    fun commit() {
        if (ability.cooldownEffect != null) {
            val cooldownSpec = GameplayEffectSpec(ability.cooldownEffect.value)
            info.source[Abilities].applyGameplayEffect(info.source, info.source, cooldownSpec)
        }
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
