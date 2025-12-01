package dev.wildware.udea.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.dsl.UdeaDsl
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.ability.Attributes
import dev.wildware.udea.get
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

    override fun toString(): String {
        return "Attribute(base=$baseValue, current=$currentValue)"
    }
}

sealed class ValueResolver {
    context(_: World)
    abstract fun getValue(entity: Entity, gameplayEffectSpec: GameplayEffectSpec): Float

    class ConstantValue(val value: Float) : ValueResolver() {
        context(_: World)
        override fun getValue(entity: Entity, gameplayEffectSpec: GameplayEffectSpec) = value
    }

    class AttributeValue(val attribute: GameplayEffectTarget) : ValueResolver() {
        context(_: World)
        override fun getValue(entity: Entity, gameplayEffectSpec: GameplayEffectSpec) =
            attribute(entity[Attributes].attributeSet).currentValue
    }

    class SetByCaller(val gameplayTag: GameplayTag) : ValueResolver() {
        context(_: World)
        override fun getValue(entity: Entity, gameplayEffectSpec: GameplayEffectSpec): Float {
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
fun value(attribute: GameplayEffectTarget) = ValueResolver.AttributeValue(attribute)

@UdeaDsl
fun value(gameplayTag: GameplayTag) = ValueResolver.SetByCaller(gameplayTag)