package dev.wildware.udea.ecs.component.ability

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.*
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.LazyList
import dev.wildware.udea.assets.emptyLazyList
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.configureNetwork
import dev.wildware.udea.gameScreen
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.network.serde.UdeaSync
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@UdeaNetworked
@Serializable
data class Abilities(
    @Transient
    val defaultAbilities: LazyList<AbilitySpec> = emptyLazyList()
) : Component<Abilities> {

    private var nextAbilitySpecId = 0

    @UdeaSync
    internal var _abilities = ArrayList<AbilitySpec>()
    val abilities: List<AbilitySpec> get() = _abilities

    @UdeaSync(inPlace = false)
    internal var _gameplayEffectSpecs = ArrayList<GameplayEffectSpec>()
    val gameplayEffectSpecs: List<GameplayEffectSpec> get() = _gameplayEffectSpecs

    override fun type() = Abilities


    override fun World.onAdd(entity: Entity) {
        defaultAbilities.get()
            .forEach { grantAbility(entity, it) }
    }

    context(world: World)
    fun applyGameplayEffectToSelf(source: Entity, gameplayEffectSpec: GameplayEffectSpec) = applyGameplayEffect(
        source, source, gameplayEffectSpec
    )

    context(world: World)
    fun applyGameplayEffect(source: Entity, target: Entity, gameplayEffectSpec: GameplayEffectSpec) {
        val alreadyApplied = _gameplayEffectSpecs.any { it.gameplayEffect == gameplayEffectSpec.gameplayEffect }
        _gameplayEffectSpecs.add(gameplayEffectSpec)
        gameplayEffectSpec.active = true

        if (!alreadyApplied) {
            (gameplayEffectSpec.dynamicCues + gameplayEffectSpec.gameplayEffect.value.cues).forEach { cue ->
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

    fun grantAbility(entity: Entity, spec: AbilitySpec) {
        if (!gameScreen.isServer) return

        _abilities.add(spec)
        spec.entity = entity
        spec.id = nextAbilitySpecId++
    }

    fun hasGameplayEffect(gameplayEffect: AssetReference<GameplayEffect>): Boolean {
        return _gameplayEffectSpecs.any { it.gameplayEffect == gameplayEffect }
    }

    fun getGameplayEffectSpec(handle: EffectHandle): GameplayEffectSpec? {
        return _gameplayEffectSpecs.find { it.handle == handle }
    }

    fun findAbilityById(abilityId: Int): AbilitySpec {
        return abilities.first { it.id == abilityId } // TODO can we do array lookup?
    }

    fun findAbilityByTag(tag: GameplayTag): AbilitySpec? {
        return abilities.firstOrNull { tag in it.allTags() }
    }

    context(world: World)
    fun findAvailableAbilityWithTags(vararg tags: GameplayTag): AbilitySpec? {
        return abilities.firstOrNull { spec ->
            tags.all { tag -> tag in spec.allTags() } && spec.canCast()
        }
    }

    companion object : UdeaComponentType<Abilities>(
        networkComponent = configureNetwork()
    )
}
