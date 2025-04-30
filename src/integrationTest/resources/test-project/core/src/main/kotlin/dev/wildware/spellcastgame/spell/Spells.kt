package dev.wildware.spellcastgame.spell

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.Assets
import dev.wildware.ecs.Blueprint
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.ecs.component.Transform
import dev.wildware.ecs.newInstance
import dev.wildware.math.Vector2
import dev.wildware.spellcastgame.spell.modifiers.Rune

object Spells {
    fun World.castSpell(
        spellElement: Asset<SpellElement>,
        runeDescriptors: List<Rune>,
        source: Entity,
        sourcePositon: Vector2 = source[Transform].position,
        heading: Vector2,
        target: Vector2?,
        size: Float = 1.0F,
        power: Float = 1.0F,
        velocity: Vector2 = Vector2.Zero,
        lifetime: Float = 10.0F,
    ): Entity {
        val projectileHeading = heading.cpy().nor()

        return Assets[Blueprint]["spell"].newInstance(this) {
            it += SpellComponent(spellElement, runeDescriptors, source).apply {
                this.target = target
                this.speed = spellElement().speed
                this.size = size
                this.power = power
                this.lifetime = lifetime
            }
        }.apply {
            this[Transform].position.set(sourcePositon)
            this[RigidBodyComponent].body.applyLinearImpulse(
                projectileHeading.scl(this[SpellComponent].speed),
                Vector2.Zero,
                true
            )
        }
    }
}
