package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.ecs.component.Transform
import dev.wildware.network.Networked
import dev.wildware.perp
import dev.wildware.spellcastgame.spell.RuneParameter
import dev.wildware.spellcastgame.spell.SpellComponent
import dev.wildware.spellcastgame.spell.Spells.castSpell
import dev.wildware.spellcastgame.spell.copyRunes
import dev.wildware.spellcastgame.spell.modifiers.AlternateModifier.NextSwap
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Networked
@Serializable
data object TrailModifier : SpellModifier {

    override val maxChildren = MaxChildren.Unlimited

    val NextTrail = RuneParameter.FloatParameter("nextSwap", 0.0F)

    override val parameters = listOf(NextTrail)

    override fun World.applyToProjectile(rune: Rune, spellComponent: SpellComponent, entity: Entity) {
        if (rune.activeTime < rune[NextSwap]) return
        rune[NextSwap] = rune.activeTime + Random.nextFloat() * 0.3F

        val rigidBody = entity[RigidBodyComponent]
        val heading = rigidBody.body.linearVelocity
            .cpy()
            .perp()

        // Deactivate the rune while we copy
        rune.active = false
        castSpell(
            spellComponent.element,
            rune.children,
            spellComponent.source,
            entity[Transform].position,
            heading.rotateDeg(Random.nextInt(-5, 5).toFloat()),
            spellComponent.target,
            spellComponent.size * 0.3F,
            0.3F
        )

        castSpell(
            spellComponent.element,
            rune.children,
            spellComponent.source,
            entity[Transform].position,
            heading.scl(-1F),
            spellComponent.target,
            spellComponent.size * 0.3F,
            0.3F
        )
        rune.active = true
    }
}
