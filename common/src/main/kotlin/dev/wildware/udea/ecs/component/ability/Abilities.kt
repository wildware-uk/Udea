package dev.wildware.udea.ecs.component.ability

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.*
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.LazyList
import dev.wildware.udea.assets.emptyLazyList
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

    val defaultAbilities: LazyList<AbilitySpec> = emptyLazyList()
) : Component<Abilities> {

    private var nextAbilitySpecId = 0

    internal val _abilities = mutableListOf<AbilitySpec>()
    val abilities: List<AbilitySpec> = _abilities

    internal val _gameplayEffectSpecs = mutableListOf<GameplayEffectSpec>()
    val gameplayEffectSpecs: List<GameplayEffectSpec> = _gameplayEffectSpecs

    init {
        defaultAbilities.get().forEach(::grantAbility)
    }

    override fun type() = Abilities

    fun <T : AttributeSet> getAttributes(): T {
        return (attributeSet as? T) ?: error("AttributeSet was not of expected type")
    }

    context(world: World)
    fun applyGameplayEffect(source: Entity, target: Entity, gameplayEffectSpec: GameplayEffectSpec) {
        val alreadyApplied = _gameplayEffectSpecs.any { it.gameplayEffect == gameplayEffectSpec.gameplayEffect }
        _gameplayEffectSpecs.add(gameplayEffectSpec)

        if (!alreadyApplied) {
            (gameplayEffectSpec.dynamicCues + gameplayEffectSpec.gameplayEffect.cues).forEach { cue ->
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

    fun grantAbility(spec: AbilitySpec) {
        _abilities.add(spec)
        spec.id = nextAbilitySpecId++
    }

    fun hasGameplayEffect(gameplayEffect: AssetReference<GameplayEffect>): Boolean {
        return _gameplayEffectSpecs.any { it.gameplayEffect == gameplayEffect }
    }

    fun findAbilityById(abilityId: Int): AbilitySpec {
        return abilities.first { it.id == abilityId } // TODO can we do array lookup?
    }

    fun findAbilityByTag(tag: GameplayTag): AbilitySpec? {
        return abilities.firstOrNull { tag in it.allTags() }
    }

    companion object : UdeaComponentType<Abilities>(
        networkComponent = configureNetwork(
            syncStrategy = Update
        )
    )
}
