package dev.wildware.udea.example.ability

import dev.wildware.udea.ability.AttributeSet
import dev.wildware.udea.ability.value
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.network.serde.UdeaSync
import kotlinx.serialization.Serializable

@Serializable
@UdeaNetworked
class CharacterAttributeSet(
    private val initHealth: Float = 100F,
    private val initMana: Float = 0F,
    private val initArmour: Float = 0F,
    private val initMagicResist: Float = 0F,
    private val initStrength: Float = 10F,
    private val initHealthRegen: Float = 1F
) : AttributeSet() {

    @UdeaSync
    val maxHealth = attribute("health", initHealth) {
        min = value(0F)
    }

    @UdeaSync
    val health = attribute("health", initHealth) {
        min = value(0F)
        max = value(initHealth)
    }

    @UdeaSync
    val maxMana = attribute("mana", initMana) {
        min = value(0F)
    }

    @UdeaSync
    val mana = attribute("mana", initMana) {
        min = value(0F)
        max = value(initMana)
    }

    @UdeaSync
    val strength = attribute("strength", initStrength)

    @UdeaSync
    val armour = attribute("armour", initArmour)

    @UdeaSync
    val magicResist = attribute("magicResist", initMagicResist)

    @UdeaSync
    val healthRegen = attribute("healthRegen", initHealthRegen)
}
