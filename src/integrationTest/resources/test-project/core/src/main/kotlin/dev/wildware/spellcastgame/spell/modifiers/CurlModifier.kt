package dev.wildware.spellcastgame.spell.modifiers

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.spellcastgame.spell.SpellComponent
import dev.wildware.udea.network.Networked
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object CurlModifier : SpellModifier {
    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
       val rigidBody = entity[RigidBodyComponent]
        val velocity = rigidBody.body.linearVelocity
        val perp = Vector2(velocity.y, -velocity.x)
            .nor()

        entity[RigidBodyComponent].body.applyLinearImpulse(perp, Vector2.Zero, true)
    }
}
