package dev.wildware.udea.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.Vector2
import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.dsl.CreateDsl
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.get
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.network.serde.UdeaSync
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.reflect.full.createInstance


/**
 * The actual execution of an ability.
 * */
abstract class AbilityExec {
    /**
     * Activate this ability.
     * */
    context(world: World, spec: AbilitySpec)
    abstract fun activate()

    /**
     * Called once per tick while the ability is active.
     * */
    context(world: World, spec: AbilitySpec)
    open fun tick() {
    }

    /**
     * Commit the cooldown/costs, marks the ability as active.
     * */
    context(world: World, spec: AbilitySpec)
    fun commitAbility() {
        spec.commit()
    }

    /**
     * Ends the ability.
     * */
    context(world: World, spec: AbilitySpec)
    fun endAbility() {
        spec.finish()
    }

    context(world: World, spec: AbilitySpec)
    open fun finish(cancelled: Boolean) {
    }
}

@UdeaNetworked
@CreateDsl
@Serializable
data class AbilitySpec(
    val ability: AssetReference<@Contextual Ability>,
    val tags: List<GameplayTag> = emptyList(),
    val setByCallerTags: Map<GameplayTag, Float> = emptyMap(),

    @UdeaSync
    var level: Int = 1,
) {
    var id = 0
    @Transient
    lateinit var entity: Entity

    @Transient
    var activeInstance: AbilityExec = ability.value.exec.createInstance()

    @UdeaSync(inPlace = false)
    var targeting: AbilityTargeting? = null

    var cooldownHandle: EffectHandle = EffectHandle.Invalid

    @Transient
    var active: Boolean = false
        internal set

    fun allTags() = ability.value.tags + tags
    fun getSetByCallerMagnitudes() = ability.value.setByCallerTags + setByCallerTags

    inline fun <reified T : AbilityTargeting> getTarget() = targeting as T

    context(world: World)
    fun finish(cancelled: Boolean = false) {
        if (active) activeInstance.finish(cancelled)
        active = false
    }

    context(_: World)
    fun activate(entity: Entity) {
        this.entity = entity

        if (!active && canCast()) {
            active = true
            activeInstance.activate()
        }
    }

    context(_: World)
    fun tick() {
        if (active) activeInstance.tick()
    }

    context(_: World, spec: AbilitySpec)
    fun commit() {
        if (ability.value.cooldownEffect != null) {
            val cooldownSpec = GameplayEffectSpec(ability.value.cooldownEffect!!.value)
            cooldownSpec.copySetByTags(getSetByCallerMagnitudes())
            spec.entity[Abilities].applyGameplayEffectToSelf(spec.entity, cooldownSpec)
            this.cooldownHandle = cooldownSpec.handle
        }

        ability.value.cost.forEach {
            val costValue = it.value
            val gameplayEffectSpec = GameplayEffectSpec(costValue)
            gameplayEffectSpec.copySetByTags(getSetByCallerMagnitudes())
            spec.entity[Abilities].applyGameplayEffectToSelf(spec.entity, gameplayEffectSpec)
        }
    }

    private fun checkCosts() {

    }

    context(_: World)
    fun canCast(): Boolean {
        val onCooldown = (entity[Abilities].getGameplayEffectSpec(cooldownHandle)?.active ?: false)
        if (onCooldown) {
            return false
        }

        val isBlocked = ability.value.blockedBy.any { entity[Abilities].hasGameplayEffectTag(it) }
        return !isBlocked
    }
}

@Serializable
sealed interface AbilityTargeting {
    @Serializable
    data object None : AbilityTargeting

    @Serializable
    data class Single(val target: Entity) : AbilityTargeting

    @Serializable
    data class Multi(val targets: List<Entity>) : AbilityTargeting

    @Serializable
    data class Location(val position: Vector2) : AbilityTargeting
}
