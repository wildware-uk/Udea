package dev.wildware.spellcastgame.spell.modifiers

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.network.Networked
import dev.wildware.spellcastgame.spell.SpellComponent
import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin

@Networked
@Serializable
data object WaveModifier : SpellModifier {
    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        val rigidBody = entity[RigidBodyComponent]
        val velocity = rigidBody.body.linearVelocity
        val perp = Vector2(velocity.y, -velocity.x)
            .nor()
            .scl(cos(spellComponent.age * 10.0F) * 1F)

        rigidBody.body.applyLinearImpulse(perp, Vector2.Zero, true)
    }
}
