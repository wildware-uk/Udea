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
        // `with(world)` wraps this whole body (issue #186). Kotlin 2.4 will not choose between
        // the system's own `EntityComponentContext` and the `context(world)` this body already
        // declares, and reports every `entity[...]`, `in` and `configure` call as ambiguous. For a
        // Fleks system those two candidates are the same `World` object, so naming one changes
        // nothing; `with` is the spelling the compiler itself suggests. The `context(world)` stays
        // because some calls in here -- `Entity.position`, `applyGameplayEffectToSelf` -- want
        // `World` as a context *parameter*, which an implicit receiver does not supply.
        // Diffing this file with whitespace ignored shows only these added lines.
        with(world) {
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
}
