package dev.wildware.udea.assets

import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.GameplayEffect
import dev.wildware.udea.dsl.CreateDsl
import kotlin.reflect.KClass

data class Ability(
    val display: AbilityDisplay,
    val exec: KClass<out AbilityExec>,
    val params: Map<String, Any> = emptyMap(),
    val cooldownEffect: AssetReference<GameplayEffect>? = null
) : Asset()

/**
 * Human-readable text for abilities.
 * */
@CreateDsl
data class AbilityDisplay(
    val name: String,
    val description: String,
)
