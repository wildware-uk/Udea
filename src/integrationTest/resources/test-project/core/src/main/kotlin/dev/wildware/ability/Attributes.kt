package dev.wildware.ability

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Attribute(
    var baseValue: Float,
    var currentValue: Float = baseValue
) {
    @Transient
    var min: ValueResolver = ValueResolver.Min

    @Transient
    var max: ValueResolver = ValueResolver.Max

    override fun toString(): String {
        return "Attribute(base=$baseValue, current=$currentValue)"
    }
}

sealed class ValueResolver {
    abstract val value: Float

    class ConstantValue(override val value: Float) : ValueResolver()

    class AttributeValue(val attribute: Attribute) : ValueResolver() {
        override val value: Float
            get() = attribute.currentValue
    }

    companion object {
        val Zero = ConstantValue(0F)
        val Max = ConstantValue(Float.MAX_VALUE)
        val Min = ConstantValue(Float.MIN_VALUE)
    }
}
