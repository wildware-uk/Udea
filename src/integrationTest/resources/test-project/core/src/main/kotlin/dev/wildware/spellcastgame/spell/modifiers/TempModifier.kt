package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.spellcastgame.spell.RuneParameter
import dev.wildware.spellcastgame.spell.SpellComponent
import kotlinx.serialization.Serializable

@Serializable
data object TempModifier : SpellModifier {
    override val maxChildren: MaxChildren = MaxChildren.Unlimited

    val Delay = RuneParameter.FloatParameter("delay", defaultValue = 0.5F)

    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        if (rune.activeTime >= rune[Delay]) {
            rune.children.forEach { it.active = false }
            rune.active = false
        }
    }
}
