package dev.wildware.udea.ability

import dev.wildware.udea.network.UdeaNetworked

@UdeaNetworked
abstract class AttributeSet {
    private val _attributes = mutableMapOf<String, Attribute>()
    val attributes: Map<String, Attribute> = _attributes

    open fun preAttributeChanged(attribute: Attribute, value: Float): Float {
        return value
    }

    fun attribute(
        name: String,
        baseValue: Float,
        init: Attribute.() -> Unit = {}
    ): Attribute {
        return Attribute(baseValue).also {
            it.init()
            _attributes[name] = it
        }
    }

    fun resetCurrentValues() {
        attributes.forEach {
            it.value.currentValue = it.value.baseValue
        }
    }
}
