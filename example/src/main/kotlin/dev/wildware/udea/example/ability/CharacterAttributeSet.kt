package dev.wildware.udea.example.ability

import dev.wildware.udea.ability.Attribute
import dev.wildware.udea.ability.AttributeSet
import dev.wildware.udea.ability.value
import dev.wildware.udea.network.UdeaNetworked
import dev.wildware.udea.network.serde.UdeaSync
import kotlinx.serialization.Serializable

@Serializable
@UdeaNetworked
class CharacterAttributeSet(
    @UdeaSync
    val maxHealth: Attribute = attribute("health", 100F) {
        min = value(0F)
    },

    @UdeaSync
    val health: Attribute = attribute("health", 100F) {
        min = value(0F)
        max = value(maxHealth)
    },
) : AttributeSet()