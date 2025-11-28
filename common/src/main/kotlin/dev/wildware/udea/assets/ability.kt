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

    val cost: List<AssetReference<GameplayEffect>> = emptyList(),

    /**
     * The maximum distance (from the target) for this ability to be cast.
     * */
    val range: Float? = null,

    /**
     * A list of gameplay tags attached to this ability.
     * */
    val tags: List<GameplayTag> = emptyList(),

    /**
     * A list of set by caller tags for this ability.
     * */
    val setByCallerTags: Map<GameplayTag, Float> = emptyMap(),

    /**
     * Set to true to prevent idle/run animations from overriding animations set by this ability.
     * */
    val blockAnimations: Boolean = false

) : Asset<Ability>()

/**
 * Human-readable text for abilities.
 * */
@CreateDsl
data class AbilityDisplay(
    val name: String,
    val description: String,
)
