package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.ecs.component.Transform
import dev.wildware.udea.network.Networked
import dev.wildware.spellcastgame.spell.SpellComponent
import dev.wildware.spellcastgame.spell.Spells.castSpell
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object ShotgunModifier : SpellModifier {
    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        val rigidBody = entity[RigidBodyComponent]
        val heading = rigidBody.body.linearVelocity
            .rotateDeg(-30F)

        rune.active = false

        repeat(10) { i ->
            castSpell(
                spellComponent.element,
                spellComponent.runes.map { it.clone() },
                spellComponent.source,
                entity[Transform].position,
                heading.rotateDeg(5F),
                spellComponent.target,
                0.5F,
                0.5F,
                lifetime = 0.2F,
            )
        }

        spellComponent.markInactive()
    }
}
