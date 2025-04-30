package dev.wildware.spellcastgame.spell

import dev.wildware.udea.network.Networked
import dev.wildware.spellcastgame.spell.modifiers.Rune
import kotlinx.serialization.Serializable

@Networked
@Serializable
data class Spell(
    val runes: List<Rune>,
    val name: String
) {

    override fun toString(): String {
        return name
    }
}
