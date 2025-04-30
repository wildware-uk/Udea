package dev.wildware.spellcastgame.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ability.AttributeModificationExec
import dev.wildware.ability.GameplayEffectSpec
import dev.wildware.ecs.component.AbilitiesComponent
import dev.wildware.spellcastgame.CharacterAttributeSet
import dev.wildware.spellcastgame.SpellCastTags

class DamageCalculationExec : AttributeModificationExec {
    override fun World.onAttributeModified(source: Entity, target: Entity, effect: GameplayEffectSpec) {
        if (effect.gameplayEffect().name == "damage") {
            val sourceStats = source[AbilitiesComponent].getAttributes<CharacterAttributeSet>()
            val targetStats = target[AbilitiesComponent].getAttributes<CharacterAttributeSet>()

            if (effect.hasTag(SpellCastTags.FIRE_DAMAGE)) {
                val resistance = maxOf(
                    targetStats.fireResistance.currentValue,
                    targetStats.magicResistance.currentValue
                )
                    .minus(sourceStats.magicPenetration.currentValue)
                    .coerceAtLeast(0F)

                effect.magnitude /= (1 + resistance / 100F)
            }
        }
    }
}
