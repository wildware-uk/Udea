package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.network.Networked
import dev.wildware.spellcastgame.spell.RuneParameter.FloatParameter
import dev.wildware.spellcastgame.spell.SpellComponent
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object SizeModifier : SpellModifier {
    val SizeMagnitude = FloatParameter("sizeMagnitude")
}
