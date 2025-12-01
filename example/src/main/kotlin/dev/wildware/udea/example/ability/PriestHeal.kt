package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilitySpec
import dev.wildware.udea.ability.AbilityTargeting
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.reference
import dev.wildware.udea.contains
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.ecs.system.Box2DSystem
import dev.wildware.udea.example.component.GameUnit
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.get
import dev.wildware.udea.position
import ktx.box2d.query

class PriestHeal : AbilityExec() {
    context(world: World, spec: AbilitySpec)
    override fun activate() {
        world.system<AnimationSetSystem>().setAnimation(spec.entity, "priest_heal")?.apply {
            onNotify("heal") {
                val toHeal = getNearbyFriendlyUnits(spec.entity, 3F)
                toHeal.forEach {
                    val gameplayEffect = GameplayEffectSpec(reference("ability/heal_over_time"))
                    gameplayEffect.addDynamicCue(PriestHealCue)
                    gameplayEffect.setSetByCallerMagnitude(Data.Heal, 5F)
                    gameplayEffect.setSetByCallerMagnitude(Data.Duration, 5F)
                    it[Abilities].applyGameplayEffect(spec.entity, it, gameplayEffect)
                }

                if (toHeal.isNotEmpty()) {
                    commitAbility()
                } else {
                    endAbility()
                }
            }

            onFinish {
                endAbility()
            }
        } ?: endAbility()
    }

    context(world: World)
    fun getNearbyFriendlyUnits(entity: Entity, radius: Float): Set<Entity> {
        val result = mutableSetOf<Entity>()
        val position = entity.position
        world.system<Box2DSystem>().box2DWorld.query(
            position.x - radius,
            position.y - radius,
            position.x + radius,
            position.y + radius
        ) { fixture ->
            val other = fixture.body?.userData as? Entity ?: return@query true
            if (GameUnit in other) {
                if (entity[Team].teamId == other[Team].teamId) {
                    if (other.position.dst(position) > radius) return@query true

                    result += other
                }
            }

            true
        }

        return result
    }
}
