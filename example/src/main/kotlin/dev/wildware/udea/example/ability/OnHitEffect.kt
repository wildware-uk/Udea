package dev.wildware.udea.example.ability

import dev.wildware.udea.ability.GameplayEffect
import dev.wildware.udea.ability.GameplayTag
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.dsl.CreateDsl

@CreateDsl
data class OnHitEffect(
    val gameplayEffect: AssetReference<GameplayEffect>,
    val setByCallerMagnitudes: Map<GameplayTag, Float>,
    val tags: List<GameplayTag> = emptyList()
)