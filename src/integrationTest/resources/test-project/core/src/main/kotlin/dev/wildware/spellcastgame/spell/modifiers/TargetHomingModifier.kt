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
data object TargetHomingModifier : SpellModifier {
    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        val target = spellComponent.target ?: return

        val spellTransform = entity[Transform]
        val diff = target.cpy().sub(spellTransform.position)
            .scl(spellComponent.speed * game.delta * 4.0F)

        entity[RigidBodyComponent].body.applyLinearImpulse(diff, Vector2.Zero, true)
    }
}
