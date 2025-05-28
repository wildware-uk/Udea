package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.game
import dev.wildware.network.Networked
import dev.wildware.spellcastgame.spell.SpellComponent
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object CurveModifier : SpellModifier {
    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        entity[RigidBodyComponent].run {
            val newHeading = body.linearVelocity
                .cpy().nor().rotateDeg(90F)
                .scl(game.delta * spellComponent.speed)
            body.applyLinearImpulse(newHeading.x, newHeading.y, 0F, 0F, true)
        }
    }
}
