package dev.wildware.udea.ecs.component.ability

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AttributeSet
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.ecs.NetworkComponent.Companion.configureNetwork
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.network.serde.UdeaSync
import kotlinx.serialization.Serializable

//@UdeaNetworked
@Serializable
data class Abilities(
    val attributeSet: AttributeSet,
) : Component<Abilities> {
    @UdeaSync(syncMode = Dirty)
    internal val _abilities = mutableListOf<AbilityExec>()
    val abilities: List<AbilityExec> = _abilities

    @UdeaSync(syncMode = Dirty)
    internal val _gameplayEffectSpecs = mutableListOf<GameplayEffectSpec>()
    val gameplayEffectSpecs: List<GameplayEffectSpec> = _gameplayEffectSpecs

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

    fun giveAbility(ability: AbilityExec) {
        _abilities.add(ability)
    }

    companion object : UdeaComponentType<Abilities>(
        networkComponent = configureNetwork()
    )
}
