package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.ability.invoke
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.ability.Attributes
import dev.wildware.udea.gameScreen
import kotlin.time.Duration.Companion.seconds

class AttributeSystem : IteratingSystem(
    family = family { all(Attributes, Abilities) }
) {
    override fun onTickEntity(entity: Entity): Unit = context(world) {
        val attributes = entity[Attributes]
        val abilities = entity[Abilities]

        attributes.attributeSet.resetCurrentValues()

        abilities.gameplayEffectSpecs
            .sortedBy { it.gameplayEffect.value.value.modifierType }
            .forEach {
                val gameplayEffect = it.gameplayEffect.value
                it.period += gameScreen.delta

                if (gameplayEffect.target != null
                    && gameplayEffect.modifierType != null
                    && gameplayEffect.magnitude != null
                ) {
                    val targetAttribute = gameplayEffect.target(attributes.attributeSet)

                    if (!it.gameplayEffect.value.isPermanent) {
                        targetAttribute.currentValue = gameplayEffect.modifierType
                            .apply(targetAttribute.currentValue, gameplayEffect.magnitude.getValue(entity, it))
                            .coerceIn(
                                targetAttribute.min.getValue(entity, it),
                                targetAttribute.max.getValue(entity, it)
                            )
                    } else if (gameplayEffect.period == null || it.period.toDouble().seconds >= gameplayEffect.period) {
                        it.period = 0F
                        targetAttribute.baseValue = gameplayEffect.modifierType
                            .apply(targetAttribute.baseValue, gameplayEffect.magnitude.getValue(entity, it))
                            .coerceIn(
                                targetAttribute.min.getValue(entity, it),
                                targetAttribute.max.getValue(entity, it)
                            )
                    }
                }

                it.duration += gameScreen.delta
            }

        (abilities.gameplayEffectSpecs as MutableList<GameplayEffectSpec>).removeIf {
            it.gameplayEffect.value.effectDuration.hasExpired(it).also { expired ->
                it.active = !expired
            }
        }
    }
}
