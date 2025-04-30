package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.spellcastgame.spell.RuneParameter
import dev.wildware.spellcastgame.spell.SpellComponent
import kotlinx.serialization.Serializable

@Serializable
sealed interface SpellModifier {
    /**
     * Doesn't limit anything, just used for ui.
     * */
    val maxChildren: MaxChildren
        get() = MaxChildren.None

    val parameters: List<RuneParameter<*>>
        get() = emptyList()

    fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {}

    /**
     * @return true if spell projectile should be consumed.
     * */
    fun onHit(entity: Entity): Boolean {
        return true
    }
}
