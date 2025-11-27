package dev.wildware.udea.ecs.component.ability

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.*
import dev.wildware.udea.assets.Ability
import dev.wildware.udea.ecs.component.SyncStrategy.Update
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.configureNetwork
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.network.serde.UdeaSync

@UdeaNetworked(
    registerKotlinXSerializer = false
)
data class Abilities(
    @UdeaSync
    val attributeSet: AttributeSet,
) : Component<Abilities> {
    internal val _abilities = mutableListOf<Ability>()
    val abilities: List<Ability> = _abilities

    internal val _gameplayEffectSpecs = mutableListOf<GameplayEffectSpec>()
    val gameplayEffectSpecs: List<GameplayEffectSpec> = _gameplayEffectSpecs

    var currentAbility: AbilityActivation? = null

    override fun type() = Abilities

    fun <T : AttributeSet> getAttributes(): T {
        return (attributeSet as? T) ?: error("AttributeSet was not of expected type")
    }

    context(world: World)
    fun applyGameplayEffect(source: Entity, target: Entity, gameplayEffectSpec: GameplayEffectSpec) {
        val alreadyApplied = _gameplayEffectSpecs.any { it.gameplayEffect == gameplayEffectSpec.gameplayEffect }
        _gameplayEffectSpecs.add(gameplayEffectSpec)

        if (!alreadyApplied) {
            gameplayEffectSpec.gameplayEffect.cues.forEach { cue ->
                cue.onGameplayEffectApplied(
                    source,
                    target,
                    gameplayEffectSpec
                )
            }
        }
    }

    fun hasGameplayEffectTag(gameplayTag: GameplayTag): Boolean {
        return _gameplayEffectSpecs.any { it.hasTag(gameplayTag) }
    }

    fun giveAbility(ability: Ability) {
        _abilities.add(ability)
    }

    companion object : UdeaComponentType<Abilities>(
        networkComponent = configureNetwork(
            syncStrategy = Update
        )
    )
}
