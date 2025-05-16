package dev.wildware.spellcastgame.spell.modifiers

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.ecs.component.Transform
import dev.wildware.game
import dev.wildware.network.Networked
import dev.wildware.spellcastgame.spell.SpellComponent
import kotlinx.serialization.Serializable

@Networked
@Serializable
data object OrbitModifier : SpellModifier {
    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        spellComponent.lifetime = 100F

        val target = spellComponent.target ?: return
        val toSource = target.cpy().sub(entity[Transform].position).nor()
        val orbit = Vector2(toSource.y, -toSource.x)
            .add(toSource)
            .scl(spellComponent.speed * game.delta * 5F)

        entity[RigidBodyComponent].body.applyLinearImpulse(orbit, Vector2.Zero, true)
    }
}
