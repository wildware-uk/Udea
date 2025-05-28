package dev.wildware.ability

import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetType
import dev.wildware.udea.assets.Assets
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty
import kotlin.time.Duration

fun gameplayEffect(
    name: String,
    target: KProperty<Attribute>,
    modifierType: dev.wildware.udea.ability.ModifierType,
    source: ValueResolver,
    effectDuration: dev.wildware.udea.ability.GameplayEffectDuration,
    period: Duration = Duration.ZERO,
    tags: List<GameplayTag> = emptyList(),
    cues: List<GameplayEffectCue> = emptyList(),
) {
    Assets[GameplayEffect][name] =
        dev.wildware.udea.ability.GameplayEffect(name, target, modifierType, source, effectDuration, period, tags, cues)
}

@Serializable
data class GameplayEffectSpec(
    val gameplayEffect: Asset<@Contextual dev.wildware.udea.ability.GameplayEffect>,
    var stacks: Int = 1,
    var magnitude: Float = 1F,
) {
    fun hasTag(tag: GameplayTag): Boolean {
        return tags.contains(tag)
    }

    val tags = mutableSetOf<GameplayTag>()
    var period: Float = 0F
    var duration: Float = 0F
}

data class GameplayEffect(
    val name: String,
    val target: KProperty<Attribute>,
    val modifierType: dev.wildware.udea.ability.ModifierType,
    val source: ValueResolver,
    var effectDuration: dev.wildware.udea.ability.GameplayEffectDuration,
    val period: Duration = Duration.ZERO,
    val tags: List<GameplayTag> = emptyList(),
    val cues: List<GameplayEffectCue> = emptyList()
) {
    companion object : AssetType<dev.wildware.udea.ability.GameplayEffect>() {
        override val id: String = "gameplay_effect"
    }
}

enum class ModifierType(
    val apply: (Float, Float) -> Float
) {
    Additive({ a, b -> a + b }),
    Multiplicative({ a, b -> a * b }),
    Override({ _, b -> b })
}

sealed class GameplayEffectDuration {
    abstract fun hasExpired(currentDuration: Float): Boolean

    data object Instant : dev.wildware.udea.ability.GameplayEffectDuration() {
        override fun hasExpired(currentDuration: Float) = true
    }

    data object Infinite : dev.wildware.udea.ability.GameplayEffectDuration() {
        override fun hasExpired(currentDuration: Float) = false
    }

    data class Duration(
        val duration: Float
    ) : dev.wildware.udea.ability.GameplayEffectDuration() {
        override fun hasExpired(currentDuration: Float) =
            currentDuration > duration
    }
}
