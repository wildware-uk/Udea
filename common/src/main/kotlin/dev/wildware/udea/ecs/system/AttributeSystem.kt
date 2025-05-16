package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.game
import kotlin.time.Duration.Companion.seconds

class AttributeSystem : IteratingSystem(
    family = family { all(Abilities) }
) {
    override fun onTickEntity(entity: Entity) {
        val abilities = entity[Abilities]

        abilities.gameplayEffectSpecs
            .sortedBy { it.gameplayEffect.modifierType }
            .forEach {
                it.period += game.delta

                if ((it.period).toDouble().seconds > it.gameplayEffect.period) {
                    it.period = 0F

                    val targetAttribute = it.gameplayEffect.target.getter.call(abilities.attributeSet)
                    targetAttribute.currentValue = it.gameplayEffect.modifierType
                        .apply(targetAttribute.currentValue, it.gameplayEffect.source.value * it.magnitude)
                        .coerceIn(targetAttribute.min.value, targetAttribute.max.value)
                }

                it.duration += game.delta
            }

        (abilities.gameplayEffectSpecs as MutableList<GameplayEffectSpec>).removeIf {
            it.gameplayEffect.effectDuration.hasExpired(it.duration)
        }
    }
}
