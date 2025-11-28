package dev.wildware.udea.ability

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.Ability
import dev.wildware.udea.dsl.CreateDsl
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.get
import kotlin.reflect.full.createInstance


/**
 * The actual execution of an ability.
 * */
abstract class AbilityExec {
    /**
     * Activate this ability.
     * */
    context(world: World, activation: AbilitySpec)
    abstract fun activate(abilityInfo: AbilityInfo)

    /**
     * Commit the cooldown/costs, marks the ability as active.
     * */
    context(world: World, spec: AbilitySpec)
    fun commitAbility(abilityInfo: AbilityInfo) {
        spec.commit(abilityInfo)
    }

    /**
     * Ends the ability.
     * */
    context(spec: AbilitySpec)
    fun endAbility() {
        spec.finish()
    }

    context(activation: AbilitySpec)
    open fun finish(cancelled: Boolean) {

    }
}


@CreateDsl
data class AbilitySpec(
    val ability: Ability,
    val tags: List<GameplayTag> = emptyList(),
    val setByCallerTags: Map<GameplayTag, Float> = emptyMap(),
    var level: Int = 1,
) {
    var id = 0
    var activeInstance: AbilityExec = ability.exec.createInstance()

    var active: Boolean = false
        private set

    fun allTags() = ability.tags + tags
    fun getSetByCallerMagnitudes() = ability.setByCallerTags + setByCallerTags

    fun finish(cancelled: Boolean = false) {
        if (active) activeInstance.finish(cancelled)
        active = false
    }

    context(_: World)
    fun activate(abilityInfo: AbilityInfo) {
        if (!active && canCast(abilityInfo)) activeInstance.activate(abilityInfo)
    }

    context(_: World)
    fun commit(info: AbilityInfo) {
        active = true
        if (ability.cooldownEffect != null) {
            val cooldownSpec = GameplayEffectSpec(ability.cooldownEffect.value)
            cooldownSpec.copySetByTags(getSetByCallerMagnitudes())
            info.source[Abilities].applyGameplayEffect(info.source, info.source, cooldownSpec)
        }

        ability.cost.forEach {
            val costValue = it.value
            val spec = GameplayEffectSpec(costValue)
            spec.copySetByTags(getSetByCallerMagnitudes())
            info.source[Abilities].applyGameplayEffect(info.source, info.source, spec)
        }
    }

    private fun checkCosts() {

    }

    context(_: World)
    private fun canCast(abilityInfo: AbilityInfo): Boolean {
        val source = abilityInfo.source
        val onCooldown =
            ability.cooldownEffect == null || source[Abilities].hasGameplayEffect(ability.cooldownEffect)
        if (onCooldown) return true

        val isBlocked = ability.blockedBy.any { source[Abilities].hasGameplayEffectTag(it) }

        return !isBlocked
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
