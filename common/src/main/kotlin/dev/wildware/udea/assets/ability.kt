package dev.wildware.udea.assets

import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.GameplayEffect
import dev.wildware.udea.ability.GameplayTag
import dev.wildware.udea.dsl.CreateDsl
import kotlin.reflect.KClass

data class Ability(
    /**
     * The execution class for this ability.
     * */
    val exec: KClass<out AbilityExec>,

    /**
     * Optional human-readable text for this ability.
     * */
    val display: AbilityDisplay? = null,

    /**
     * Optional parameters for this ability.
     * */
    val params: Map<String, Any> = emptyMap(),

    /**
     * This effect will be applied to casting entities, and checked before the ability is executed.
     * */
    val cooldownEffect: AssetReference<GameplayEffect>? = null,

    /**
     * A list of [GameplayTag]s to block this ability from being activated.
     * */
    val blockedBy: List<GameplayTag> = emptyList(),

    /**
     * The maximum distance (from the target) for this ability to be cast.
     * */
    val range: Float? = null
) : Asset<Ability>()

/**
 * Human-readable text for abilities.
 * */
@CreateDsl
data class AbilityDisplay(
    val name: String,
    val description: String,
)
