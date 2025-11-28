package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.AbilitySpec
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.assets.Assets
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
    context(world: World, activation: AbilitySpec)
    override fun activate(abilityInfo: AbilityInfo) {
        commitAbility(abilityInfo)

        world.system<AnimationSetSystem>().setAnimation(abilityInfo.source, "priest_heal")?.apply {
            onNotify("heal") {
                val toHeal = getNearbyFriendlyUnits(abilityInfo.source, 2F)

                toHeal.forEach {
                    val gameplayEffect = GameplayEffectSpec(Assets["ability/heal"])
                    gameplayEffect.addDynamicCue(PriestHealCue)
                    gameplayEffect.setSetByCallerMagnitude(Data.Heal, 10F)
                    it[Abilities].applyGameplayEffect(abilityInfo.source, it, gameplayEffect)
                }
            }

            onFinish {
                endAbility()
            }
        }
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
