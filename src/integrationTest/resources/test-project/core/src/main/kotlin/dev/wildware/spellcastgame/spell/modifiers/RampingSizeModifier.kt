package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.game
import dev.wildware.spellcastgame.spell.SpellComponent
import dev.wildware.network.Networked
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object RampingSizeModifier : SpellModifier {
    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        spellComponent.size += game.delta
        spellComponent.power += game.delta
    }
}
