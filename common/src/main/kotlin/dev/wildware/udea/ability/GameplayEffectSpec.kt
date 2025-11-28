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
) {
    private val setByCallerMagnitudes = mutableMapOf<GameplayTag, Float>()
    private val dynamicTags = mutableSetOf<GameplayTag>()
    val dynamicCues = mutableSetOf<GameplayEffectCue>()

    fun hasTag(tag: GameplayTag): Boolean {
        return gameplayEffect.tags.contains(tag) || dynamicTags.contains(tag)
    }

    fun copySetByTags(tags: Map<GameplayTag, Float>) {
        setByCallerMagnitudes.putAll(tags)
    }

    fun setSetByCallerMagnitude(tag: GameplayTag, magnitude: Float) {
        setByCallerMagnitudes[tag] = magnitude
    }

    fun getSetByCallerMagnitude(tag: GameplayTag): Float {
        return setByCallerMagnitudes[tag] ?: 0F
    }

    fun addDynamicTag(tag: GameplayTag) {
        dynamicTags.add(tag)
    }

    fun addDynamicCue(cue: GameplayEffectCue) {
        dynamicCues.add(cue)
    }

    operator fun contains(tag: GameplayTag) = hasTag(tag)

    var period: Float = 0F
    var duration: Float = 0F
}

data class GameplayEffect(
    val target: KProperty<Attribute>? = null,
    val modifierType: ModifierType? = null,
    val magnitude: ValueResolver? = null,
    var effectDuration: GameplayEffectDuration,
    val period: Duration = Duration.ZERO,
    val tags: List<GameplayTag> = emptyList(),
    val cues: List<GameplayEffectCue> = emptyList()
) : Asset<GameplayEffect>()

enum class ModifierType(
    val apply: (Float, Float) -> Float
) {
    Additive({ a, b -> a + b }),
    Multiplicative({ a, b -> a * b }),
    Override({ _, b -> b })
}

sealed class GameplayEffectDuration {
    abstract fun hasExpired(spec: GameplayEffectSpec): Boolean

    data object Instant : GameplayEffectDuration() {
        override fun hasExpired(spec: GameplayEffectSpec) = true
    }

    data object Infinite : GameplayEffectDuration() {
        override fun hasExpired(spec: GameplayEffectSpec) = false
    }

    data class Duration(
        val duration: Float
    ) : GameplayEffectDuration() {
        override fun hasExpired(spec: GameplayEffectSpec) =
            spec.duration > duration
    }

    data class SetByCaller(val gameplayTag: GameplayTag) : GameplayEffectDuration() {
        override fun hasExpired(spec: GameplayEffectSpec) = spec.duration > spec.getSetByCallerMagnitude(gameplayTag)
    }
}

@UdeaDsl
fun instant() = GameplayEffectDuration.Instant

fun infinite() = GameplayEffectDuration.Infinite

@UdeaDsl
fun duration(duration: Float) = GameplayEffectDuration.Duration(duration)

@UdeaDsl
fun duration(gameplayTag: GameplayTag) = GameplayEffectDuration.SetByCaller(gameplayTag)
