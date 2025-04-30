package dev.wildware.spellcastgame.spell

import com.badlogic.gdx.graphics.g2d.ParticleEffect
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.FamilyOnAdd
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.ability.GameplayEffectSpec
import dev.wildware.ecs.component.AbilitiesComponent
import dev.wildware.ecs.component.Box2DLight
import dev.wildware.ecs.component.Dead
import dev.wildware.ecs.component.Particles
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.ecs.system.Box2DSystem
import dev.wildware.game

class SpellSystem : IteratingSystem(
    family = family { all(SpellComponent, RigidBodyComponent, Particles) }
), FamilyOnAdd {
    override fun onAddEntity(entity: Entity) {
        val spell = entity[SpellComponent]
        entity[RigidBodyComponent].body.gravityScale =
            if (spell.element().gravity) 1.0f else 0.0f

        val particleEffect = ParticleEffect(spell.element().particleEffect)
        particleEffect.scaleEffect(spell.size)
        entity[Particles].particleEffects += particleEffect

        val lights = entity[Box2DLight]
        lights.pointLight.color = spell.element().colour
        lights.pointLight.distance = spell.size * 2.0F
    }

    override fun onInit() {
        world.system<Box2DSystem>().onCollide { a, b ->
            val spell = a.getOrNull(SpellComponent) ?: return@onCollide
            if (!spell.active) return@onCollide
            if (spell.age < 0.04f) return@onCollide

            b.getOrNull(AbilitiesComponent)?.apply {
                world.applyGameplayEffect(a, b, GameplayEffectSpec(spell.element().onHitEffect))
            }

            b.getOrNull(SpellComponent)?.let {
                return@onCollide
            }

            spell.markInactive()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val spell = entity[SpellComponent]

        if (spell.active) {
            if (game.isServer) {
                tickActive(spell, entity)
            }
        } else {
            tickInactive(entity)
        }
    }

    private fun tickActive(
        spell: SpellComponent,
        entity: Entity
    ) {
        spell.runes.forEach { rune ->
            with(rune) {
                if (active) {
                    world.apply(entity, spell)
                }
            }
        }

        val rigidBodyComponent = entity[RigidBodyComponent]
        val limitedVelocity = rigidBodyComponent.body.linearVelocity.limit(spell.speed)
        rigidBodyComponent.body.linearVelocity = limitedVelocity

        spell.age += game.delta
        if (spell.age >= spell.lifetime) {
            spell.markInactive()
        }
    }

    private fun tickInactive(entity: Entity) {
        entity[Box2DLight].pointLight.isActive = false
        entity[RigidBodyComponent].body.isActive = false

        entity[Particles].particleEffects.forEach {
            val emitter = it.emitters.first()
            if (!emitter.isComplete) {
                emitter.allowCompletion()
            } else {
                entity.configure {
                    it += Dead
                }
            }
        }
    }
}
