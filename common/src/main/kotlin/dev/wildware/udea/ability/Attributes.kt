package dev.wildware.udea.ability

import dev.wildware.udea.assets.dsl.UdeaDsl
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.network.serde.UdeaSync
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@UdeaNetworked
data class Attribute(
    @UdeaSync
    var baseValue: Float,

    @UdeaSync
    var currentValue: Float = baseValue
) {
    @Transient
    var min: ValueResolver = ValueResolver.Min

    @Transient
    var max: ValueResolver = ValueResolver.Max

    fun forceValue(newValue: Float) {
        this.currentValue = newValue
        this.baseValue = newValue
    }

    override fun toString(): String {
        return "Attribute(base=$baseValue, current=$currentValue)"
    }
}

sealed class ValueResolver {
    abstract fun getValue(gameplayEffectSpec: GameplayEffectSpec): Float

    class ConstantValue(val value: Float) : ValueResolver() {
        override fun getValue(gameplayEffectSpec: GameplayEffectSpec) = value
    }

    class AttributeValue(val attribute: Attribute) : ValueResolver() {
        override fun getValue(gameplayEffectSpec: GameplayEffectSpec) = attribute.currentValue
    }

    class SetByCaller(val gameplayTag: GameplayTag) : ValueResolver() {
        override fun getValue(gameplayEffectSpec: GameplayEffectSpec): Float {
            return gameplayEffectSpec.getSetByCallerMagnitude(gameplayTag)
        }
    }

    companion object {
        val Zero = ConstantValue(0F)
        val Max = ConstantValue(Float.MAX_VALUE)
        val Min = ConstantValue(Float.MIN_VALUE)
    }
}

@UdeaDsl
fun value(value: Float) = ValueResolver.ConstantValue(value)

@UdeaDsl
fun value(attribute: Attribute) = ValueResolver.AttributeValue(attribute)

@UdeaDsl
fun value(gameplayTag: GameplayTag) = ValueResolver.SetByCaller(gameplayTag)