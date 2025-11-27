package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.AbilityActivation
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.base.Debug
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.example.component.NPC
import dev.wildware.udea.get
import dev.wildware.udea.position

class NPCMeleeAttack(abilityActivation: AbilityActivation) : AbilityExec(abilityActivation) {
    context(world: World)
    override fun activate(abilityInfo: AbilityInfo) {
        val source = abilityInfo.source
        val target = abilityInfo.target
        val attackAnimation = source[NPC].attackAnimation

        if (target == null) {
            return endAbility()
        }

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

                val hitEffect = GameplayEffectSpec(Assets["ability/melee_damage"])
                target[Abilities].applyGameplayEffect(source, target, hitEffect)

                val hitStun = GameplayEffectSpec(Assets["ability/melee_stun"])
                target[Abilities].applyGameplayEffect(source, target, hitStun)

                val knockback = diff.nor().scl(.2F)
                target[Body].body.applyLinearImpulse(knockback, Zero, true)
            }

            onFinish {
                source[Debug].addMessage("Animation Finished", 0.5F)
                endAbility()
            }
        }
    }
}
