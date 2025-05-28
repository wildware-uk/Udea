package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.ecs.component.Transform
import dev.wildware.network.Networked
import dev.wildware.spellcastgame.spell.SpellComponent
import dev.wildware.spellcastgame.spell.Spells.castSpell
import dev.wildware.spellcastgame.spell.copyRunes
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Networked
@Serializable
data object ExplodeModifier : SpellModifier {
    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        val rigidBody = entity[RigidBodyComponent]
        val heading = rigidBody.body.linearVelocity
            .rotateDeg(Random.nextInt(90).toFloat())

        val copies = (spellComponent.size / 0.05F).toInt()
        val increment = 360F / copies

        rune.active = false

        repeat(copies) { i ->
            castSpell(
                spellComponent.element,
                spellComponent.runes.copyRunes(),
                spellComponent.source,
                entity[Transform].position,
                heading.rotateDeg(increment),
                spellComponent.target,
                spellComponent.size * 0.3F,
                0.3F
            )
        }

        spellComponent.markInactive()
    }
}
