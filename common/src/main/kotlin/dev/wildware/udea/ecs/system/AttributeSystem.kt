package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.gameScreen
import kotlin.time.Duration.Companion.seconds

class AttributeSystem : IteratingSystem(
    family = family { all(Abilities) }
) {
    override fun onTickEntity(entity: Entity) {
        val abilities = entity[Abilities]

        abilities.gameplayEffectSpecs
            .sortedBy { it.gameplayEffect.modifierType }
            .forEach {
                it.period += gameScreen.delta

                if ((it.period).toDouble().seconds > it.gameplayEffect.period) {
                    it.period = 0F

                    if (it.gameplayEffect.target != null
                        && it.gameplayEffect.modifierType != null
                        && it.gameplayEffect.magnitude != null
                    ) {
                        val targetAttribute = it.gameplayEffect.target.getter.call(abilities.attributeSet)
                        targetAttribute.currentValue = it.gameplayEffect.modifierType
                            .apply(targetAttribute.currentValue, it.gameplayEffect.magnitude.getValue(it))
                            .coerceIn(targetAttribute.min.getValue(it), targetAttribute.max.getValue(it))
                    }
                }

                it.duration += gameScreen.delta
            }

        (abilities.gameplayEffectSpecs as MutableList<GameplayEffectSpec>).removeIf {
            it.gameplayEffect.effectDuration.hasExpired(it)
        }
    }
}
