package dev.wildware.udea.example.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.base.Dead
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.example.component.GameUnit
import dev.wildware.udea.example.component.Projectile
import dev.wildware.udea.example.component.Team

class ProjectileSystem : IteratingSystem(
    family { all(Projectile) }
) {
    override fun onTickEntity(entity: Entity) = context(world) {
        entity[Transform].rotation = entity[Body].body.linearVelocity.angleRad()

        entity[Body].touching.forEach { other ->
            if (GameUnit !in other) return@forEach
            if (other[GameUnit].isDead) return@forEach
            if (entity[Team].teamId == other[Team].teamId) return@forEach

            entity.configure {
                entity += Dead
            }

            entity[Projectile].onHitEffects.forEach {
                val gameplayEffectSpec = GameplayEffectSpec(it.gameplayEffect.value)
                it.setByCallerMagnitudes.forEach { (tag, magnitude) ->
                    gameplayEffectSpec.setSetByCallerMagnitude(tag, magnitude)
                }

                it.tags.forEach { gameplayEffectSpec.addDynamicTag(it) }
                other[Abilities].applyGameplayEffect(entity[Projectile].owner!!, other, gameplayEffectSpec)
            }

            return
        }
    }
}
