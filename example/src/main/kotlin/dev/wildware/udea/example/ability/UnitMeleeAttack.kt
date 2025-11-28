package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.AbilitySpec
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.animation.AnimationMapHolder
import dev.wildware.udea.ecs.component.base.Debug
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.example.character.GameUnitAnimationMap
import dev.wildware.udea.get
import dev.wildware.udea.position

class UnitMeleeAttack : AbilityExec() {
    context(world: World, activation: AbilitySpec)
    override fun activate(abilityInfo: AbilityInfo) {
        val source = abilityInfo.source
        val target = abilityInfo.target
        val attackAnimation = source[AnimationMapHolder].animationMap<GameUnitAnimationMap>().attack

        if (target == null) {
            return endAbility()
        }

        commitAbility(abilityInfo)

        source[Debug].addMessage("Animation Started", 0.5F)

        world.system<AnimationSetSystem>().setAnimation(source, attackAnimation)?.apply {
            onNotify("attack_hit") {
                source[Debug].addMessage("Animation Hit", 0.5F)
                val target = abilityInfo.target ?: return@onNotify
                val diff = target.position.cpy().sub(source.position)

                if (diff.len() > 0.5F) {
                    source[Debug].addMessage("Missed!", 0.5F)
                    endAbility()
                    return@onNotify
                }

                val damageEffect = GameplayEffectSpec(Assets["ability/damage"])
                damageEffect.setSetByCallerMagnitude(Data.Damage, -10F)
                damageEffect.addDynamicTag(Damage.Physical)
                target[Abilities].applyGameplayEffect(source, target, damageEffect)

                val stunEffect = GameplayEffectSpec(Assets["ability/stun"])
                stunEffect.setSetByCallerMagnitude(Data.Duration, 0.5F)
                target[Abilities].applyGameplayEffect(source, target, stunEffect)

                val knockbackEffect = GameplayEffectSpec(Assets["ability/knockback"])
                knockbackEffect.setSetByCallerMagnitude(Data.Knockback, .5F)
                target[Abilities].applyGameplayEffect(source, target, knockbackEffect)

                val knockback = diff.nor().scl(.3F)
                target[Body].body.applyLinearImpulse(knockback, Zero, true)
            }

            onFinish {
                source[Debug].addMessage("Animation Finished", 0.5F)
                endAbility()
            }
        }
    }
}
