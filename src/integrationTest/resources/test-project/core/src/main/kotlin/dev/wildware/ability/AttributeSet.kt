package dev.wildware.ability

import kotlinx.serialization.Serializable

@Serializable
abstract class AttributeSet {
    open fun preAttributeChanged(attribute: Attribute, value: Float): Float {
        return value
    }

    companion object {
        fun attribute(
            name: String,
            baseValue: Float,
            init: Attribute.() -> Unit = {}
        ): Attribute {
            return Attribute(baseValue).also {
                it.init()
            }
        }
    }
}
