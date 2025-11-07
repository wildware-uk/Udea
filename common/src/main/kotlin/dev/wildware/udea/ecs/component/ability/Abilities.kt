package dev.wildware.udea.ecs.component.ability

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.ability.Ability
import dev.wildware.udea.ability.AttributeSet
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.ecs.NetworkComponent.Companion.configureNetwork
import dev.wildware.udea.ecs.component.UdeaComponentType
import kotlinx.serialization.Serializable

@UdeaNetworked
@Serializable
data class Abilities(
    val attributeSet: AttributeSet,
) : Component<Abilities> {
    private val _abilities = mutableListOf<Ability>()
    val abilities: List<Ability> = _abilities

    private val _gameplayEffectSpecs = mutableListOf<GameplayEffectSpec>()
    val gameplayEffectSpecs: List<GameplayEffectSpec> = _gameplayEffectSpecs

    override fun type() = Abilities

    fun <T : AttributeSet> getAttributes(): T {
        return (attributeSet as? T) ?: error("AttributeSet was not of expected type")
    }

    fun World.applyGameplayEffect(source: Entity, target: Entity, gameplayEffectSpec: GameplayEffectSpec) {
        val alreadyApplied = _gameplayEffectSpecs.any { it.gameplayEffect == gameplayEffectSpec.gameplayEffect }
        _gameplayEffectSpecs.add(gameplayEffectSpec)

        if (!alreadyApplied) {
            gameplayEffectSpec.gameplayEffect.cues.forEach { cue ->
                with(cue) {
                    onGameplayEffectApplied(
                        source,
                        target,
                        gameplayEffectSpec.gameplayEffect
                    )
                }
            }
        }
    }

    fun giveAbility(ability: Ability) {
        _abilities.add(ability)
    }

    companion object : UdeaComponentType<Abilities>(
        networkComponent = configureNetwork()
    )
}
