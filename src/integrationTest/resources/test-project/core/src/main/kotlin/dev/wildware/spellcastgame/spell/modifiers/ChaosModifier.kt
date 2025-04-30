package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.game
import dev.wildware.math.Vector2
import dev.wildware.udea.network.Networked
import dev.wildware.spellcastgame.spell.SpellComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.*

@Networked
@Serializable
object ChaosModifier : SpellModifier {
    @Transient
    val random = Random()

    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        entity[RigidBodyComponent].run {
            val newHeading = Vector2(body.linearVelocity.y, -body.linearVelocity.x)
                .nor()
                .scl(10F * random.nextGaussian().toFloat() * spellComponent.speed * game.delta)

            body.applyLinearImpulse(newHeading.x, newHeading.y, 0F, 0F, true)
        }
    }
}
