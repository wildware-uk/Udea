package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.network.Networked
import dev.wildware.spellcastgame.spell.RuneParameter
import dev.wildware.spellcastgame.spell.SpellComponent
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object AlternateModifier : SpellModifier {
    val Period = RuneParameter.FloatParameter("period", 0.5F)
    val State = RuneParameter.BoolParameter("state", false)
    val NextSwap = RuneParameter.FloatParameter("nextSwap", 0.0F)

    override val maxChildren = MaxChildren.Value(2)
    override val parameters = listOf(Period, State, NextSwap)

    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        val (a, b) = rune.children
        val period: Float = rune[Period]
        val nextSwap: Float = rune[NextSwap]
        val state: Boolean = rune[State]

        if (rune.activeTime >= nextSwap) {
            rune[NextSwap] = rune.activeTime + period
            rune[State] = !rune[State]
        }

        with(if (state) a else b) {
            apply(entity, spellComponent)
        }
    }
}
