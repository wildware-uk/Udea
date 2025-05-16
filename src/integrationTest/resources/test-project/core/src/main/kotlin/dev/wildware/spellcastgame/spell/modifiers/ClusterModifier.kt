package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.ecs.component.Transform
import dev.wildware.network.Networked
import dev.wildware.spellcastgame.spell.SpellComponent
import dev.wildware.spellcastgame.spell.Spells.castSpell
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object ClusterModifier : SpellModifier {
    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        val rigidBody = entity[RigidBodyComponent]
        val heading = rigidBody.body.linearVelocity
            .rotateDeg(-30F)

        val copies = (spellComponent.size / 0.3F).toInt()

        rune.active = false

        repeat(copies) { i ->
            castSpell(
                spellComponent.element,
                spellComponent.runes.map { it.clone() },
                spellComponent.source,
                entity[Transform].position,
                heading.rotateDeg(15F),
                spellComponent.target,
                0.6F,
                0.6F
            )
        }

        spellComponent.markInactive()
    }
}
