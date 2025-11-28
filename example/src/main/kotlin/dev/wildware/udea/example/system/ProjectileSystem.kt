package dev.wildware.udea.example.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.base.Dead
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.Box2DSystem
import dev.wildware.udea.example.component.GameUnit
import dev.wildware.udea.example.component.Projectile
import dev.wildware.udea.example.component.Team

class ProjectileSystem : IteratingSystem(
    family { all(Projectile) }
) {
    override fun onInit() {
        world.system<Box2DSystem>().onCollide { a, b ->
            if (Projectile !in a || GameUnit !in b) return@onCollide
            if (b[GameUnit].isDead) return@onCollide
            if (a[Team].teamId == b[Team].teamId) return@onCollide

            a.configure {
                a += Dead
            }

            a[Projectile].onHitEffects.forEach {
                val gameplayEffectSpec = GameplayEffectSpec(it.gameplayEffect.value)
                it.setByCallerMagnitudes.forEach { (tag, magnitude) ->
                    gameplayEffectSpec.setSetByCallerMagnitude(tag, magnitude)
                }

                it.tags.forEach { gameplayEffectSpec.addDynamicTag(it) }
                b[Abilities].applyGameplayEffect(a[Projectile].owner!!, b, gameplayEffectSpec)
            }

        }
    }

    override fun onTickEntity(entity: Entity) {
        entity[Transform].rotation = entity[Body].body.linearVelocity.angleRad()
    }
}
