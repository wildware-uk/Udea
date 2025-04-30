package dev.wildware.spellcastgame

import dev.wildware.ability.Attribute
import dev.wildware.ability.AttributeSet
import dev.wildware.ability.ValueResolver.AttributeValue
import dev.wildware.ability.ValueResolver.ConstantValue
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
open class CharacterAttributeSet(
    val maxHealth: Attribute = attribute("health", 100F) {
        min = ConstantValue(0F)
    },

    val health: Attribute = attribute("health", 100F) {
        min = ConstantValue(0F)
        max = AttributeValue(maxHealth)
    },

    val moveSpeed: Attribute = attribute("moveSpeed", 1F) {
        min = ConstantValue(0F)
    },

    val armor: Attribute = attribute("armor", 0F),

    val magicPenetration: Attribute = attribute("magicPenetration", 0F),
    val magicResistance: Attribute = attribute("magicResistance", 0F),
    val fireResistance: Attribute = attribute("fireResistance", 0F),
) : AttributeSet() {


    override fun toString(): String {
        return "CharacterAttributeSet(maxHealth=$maxHealth, health=$health, moveSpeed=$moveSpeed, armor=$armor, magicPenetration=$magicPenetration, magicResistance=$magicResistance, fireResistance=$fireResistance)"
    }
}
