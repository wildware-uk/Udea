package dev.wildware.ecs.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.Asset
import dev.wildware.ability.Ability
import dev.wildware.ability.AttributeSet
import dev.wildware.ability.GameplayEffectSpec
import dev.wildware.network.NetworkComponent
import dev.wildware.udea.network.Networked
import kotlinx.serialization.Serializable

@Networked
@Serializable
data class AbilitiesComponent(
    val attributeSet: AttributeSet,
) : Component<AbilitiesComponent> {
    private val _abilities = mutableListOf<Asset<Ability>>()
    val abilities: List<Asset<Ability>> = _abilities

    private val _gameplayEffectSpecs = mutableListOf<GameplayEffectSpec>()
    val gameplayEffectSpecs: List<GameplayEffectSpec> = _gameplayEffectSpecs

    override fun type()= AbilitiesComponent

    fun <T : AttributeSet> getAttributes(): T {
        return (attributeSet as? T) ?: error("AttributeSet was not of expected type")
    }

    fun World.applyGameplayEffect(source: Entity, target: Entity, gameplayEffectSpec: GameplayEffectSpec) {
        val alreadyApplied = _gameplayEffectSpecs.any { it.gameplayEffect == gameplayEffectSpec.gameplayEffect }
        _gameplayEffectSpecs.add(gameplayEffectSpec)

        if (!alreadyApplied) {
            gameplayEffectSpec.gameplayEffect().cues.forEach { cue ->
                with(cue) {
                    onGameplayEffectApplied(
                        source,
                        target,
                        gameplayEffectSpec.gameplayEffect()
                    )
                }
            }
        }
    }

    fun giveAbility(ability: Asset<Ability>) {
        _abilities.add(ability)
    }

    companion object : ComponentType<AbilitiesComponent>(), NetworkComponent<AbilitiesComponent>
}
