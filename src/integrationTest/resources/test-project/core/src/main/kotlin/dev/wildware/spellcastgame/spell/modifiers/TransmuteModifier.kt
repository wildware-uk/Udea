package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.ecs.component.Transform
import dev.wildware.network.Networked
import dev.wildware.spellcastgame.spell.RuneParameter.SpellElementParameter
import dev.wildware.spellcastgame.spell.SpellComponent
import dev.wildware.spellcastgame.spell.Spells.castSpell
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object TransmuteModifier : SpellModifier {

    val spellElement = SpellElementParameter("spellElement")

    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        val rigidBody = entity[RigidBodyComponent]
        val heading = rigidBody.body.linearVelocity

        rune.active = false

        castSpell(
            rune[spellElement],
            spellComponent.runes.map { it.clone() },
            spellComponent.source,
            entity[Transform].position,
            heading,
            spellComponent.target,
        )

        spellComponent.markInactive()
    }
}
