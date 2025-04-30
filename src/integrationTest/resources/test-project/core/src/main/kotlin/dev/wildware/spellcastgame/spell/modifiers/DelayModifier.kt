package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.network.Networked
import dev.wildware.spellcastgame.spell.RuneParameter
import dev.wildware.spellcastgame.spell.SpellComponent
import dev.wildware.spellcastgame.spell.modifiers.MaxChildren.Unlimited
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object DelayModifier : SpellModifier {
    val delay = RuneParameter.FloatParameter("delay", defaultValue = 0.5F)

    override val maxChildren = Unlimited

    override val parameters: List<RuneParameter<*>> = listOf(delay)

    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        val delay: Float = rune[delay]

        with(rune) {
            if (activeTime >= delay) {
                children.forEach {
                    with(it) { apply(entity, spellComponent) }
                }
            }
        }
    }
}
