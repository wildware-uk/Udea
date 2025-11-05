package dev.wildware.udea.ability

import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.dsl.UdeaDsl
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty
import kotlin.time.Duration

@Serializable
data class GameplayEffectSpec(
    val gameplayEffect: @Contextual GameplayEffect,
    var stacks: Int = 1,
    var magnitude: Float = 1F,
) {
    fun hasTag(tag: GameplayTag): Boolean {
        return tags.contains(tag)
    }

    operator fun contains(tag: GameplayTag) = hasTag(tag)

    val tags = mutableSetOf<GameplayTag>()
    var period: Float = 0F
    var duration: Float = 0F
}

data class GameplayEffect(
    val target: KProperty<Attribute>,
    val modifierType: ModifierType,
    val source: ValueResolver,
    var effectDuration: GameplayEffectDuration,
    val period: Duration = Duration.ZERO,
    val tags: List<GameplayTag> = emptyList(),
    val cues: List<GameplayEffectCue> = emptyList()
) : Asset()

enum class ModifierType(
    val apply: (Float, Float) -> Float
) {
    Additive({ a, b -> a + b }),
    Multiplicative({ a, b -> a * b }),
    Override({ _, b -> b })
}

sealed class GameplayEffectDuration {
    abstract fun hasExpired(currentDuration: Float): Boolean

    data object Instant : GameplayEffectDuration() {
        override fun hasExpired(currentDuration: Float) = true
    }

    data object Infinite : GameplayEffectDuration() {
        override fun hasExpired(currentDuration: Float) = false
    }

    data class Duration(
        val duration: Float
    ) : GameplayEffectDuration() {
        override fun hasExpired(currentDuration: Float) =
            currentDuration > duration
    }
}
