package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.network.Networked
import dev.wildware.spellcastgame.spell.RuneParameter
import dev.wildware.spellcastgame.spell.SpellComponent
import dev.wildware.spellcastgame.spell.modifiers.MaxChildren.Unlimited
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Networked
@Serializable
data object RandomModifier : SpellModifier {
    val ChildIndex = RuneParameter.IntParameter("childIndex")
    override val maxChildren = Unlimited

    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        if(rune.getOrNull(ChildIndex) == null) {
            val randomIndex = Random.nextInt(0, rune.children.size)
            rune[ChildIndex] = randomIndex
        }

        val child = rune.children[rune[ChildIndex]]
        with(child) {
            apply(entity, spellComponent)
        }
    }
}
