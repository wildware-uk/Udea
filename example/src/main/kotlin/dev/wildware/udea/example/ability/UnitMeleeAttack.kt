package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilitySpec
import dev.wildware.udea.ability.AbilityTargeting
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.reference
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.ability.Attributes
import dev.wildware.udea.ecs.component.animation.AnimationMapHolder
import dev.wildware.udea.ecs.component.audio.AudioMapHolder
import dev.wildware.udea.ecs.component.base.Debug
import dev.wildware.udea.ecs.component.control.CharacterController
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.ecs.system.SoundSystem
import dev.wildware.udea.example.character.GameUnitAnimationMap
import dev.wildware.udea.example.character.GameUnitSoundMap
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.example.getUnitsWithin
import dev.wildware.udea.gameScreen
import dev.wildware.udea.get
import dev.wildware.udea.getOrNull
import dev.wildware.udea.position

class UnitMeleeAttack : AbilityExec() {
    context(world: World, spec: AbilitySpec)
    override fun activate() {
        val attackAnimation = spec.entity[AnimationMapHolder].animationMap<GameUnitAnimationMap>().attack

        commitAbility()

        spec.entity[Debug].addMessage("Animation Started", 0.5F)

        spec.entity.getOrNull(AudioMapHolder)?.get<GameUnitSoundMap>()?.attack?.value?.let {
            world.system<SoundSystem>().playSoundAtPosition(it, spec.entity.position)
        }

        world.system<AnimationSetSystem>().setAnimation(spec.entity, attackAnimation)?.apply {
            onNotify("swoosh") {
                world.system<SoundSystem>().playSoundAtPosition(
                    Assets.get<SoundCue>("sounds/melee_swoosh_sound_cue"),
                    spec.entity.position
                )
            }

            onNotify("attack_hit") {
                spec.targeting = findTarget() ?: return@onNotify

                spec.entity[Debug].addMessage("Animation Hit", 0.5F)
                val target = spec.getTarget<AbilityTargeting.Single>().target
                val diff = target.position.cpy().sub(spec.entity.position)

                if (diff.len() > 0.8F) {
                    spec.entity[Debug].addMessage("Missed!", 0.5F)
                    endAbility()
                    return@onNotify
                }

                if(gameScreen.isServer) {
                    val strength = spec.entity[Attributes].getAttributes<CharacterAttributeSet>().strength.currentValue

                    val damageEffect = GameplayEffectSpec(reference("ability/damage"))
                    damageEffect.setSetByCallerMagnitude(Data.Damage, -strength)
                    damageEffect.addDynamicTag(Damage.Physical)
                    damageEffect.addDynamicCue(MeleeDamageCue)
                    target[Abilities].applyGameplayEffect(spec.entity, target, damageEffect)

                    val stunEffect = GameplayEffectSpec(reference("ability/stun"))
                    stunEffect.setSetByCallerMagnitude(Data.Duration, 0.5F)
                    target[Abilities].applyGameplayEffect(spec.entity, target, stunEffect)

                    val knockbackEffect = GameplayEffectSpec(reference("ability/knockback"))
                    knockbackEffect.setSetByCallerMagnitude(Data.Knockback, .1F)
                    target[Abilities].applyGameplayEffect(spec.entity, target, knockbackEffect)

                    val knockback = diff.nor().scl(.3F)
                    target[Body].body.applyLinearImpulse(knockback, Zero, true)
                }
            }

            onFinish {
                spec.entity[Debug].addMessage("Animation Finished", 0.5F)
                endAbility()
            }
        } ?: endAbility()
    }

    context(world: World, spec: AbilitySpec)
    private fun findTarget(): AbilityTargeting.Single? {
        val controller = spec.entity[CharacterController]

        return getUnitsWithin(spec.entity, 1.0F)
            .filter { it[Team] != spec.entity[Team] }
            .minByOrNull { it.position.dst(spec.entity.position.cpy().add(controller.movement)) }
            ?.let { AbilityTargeting.Single(it) }
    }
}
