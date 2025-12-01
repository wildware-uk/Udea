package dev.wildware.udea.example.ability

import com.badlogic.gdx.physics.box2d.BodyDef.BodyType.DynamicBody
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType.KinematicBody
import com.github.quillraven.fleks.World
import dev.wildware.udea.Mouse
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilitySpec
import dev.wildware.udea.ability.AbilityTargeting
import dev.wildware.udea.ability.GameplayEffect
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.reference
import dev.wildware.udea.ecs.component.ability.Abilities
import dev.wildware.udea.ecs.component.ability.Attributes
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.ecs.system.SoundSystem
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.example.getUnitsWithin
import dev.wildware.udea.get
import dev.wildware.udea.position

class OrcSpinAttack : AbilityExec() {
    context(world: World, spec: AbilitySpec)
    override fun activate() {
        val source = spec.entity

        spec.targeting = AbilityTargeting.Location(Mouse.mouseWorldPos)

        commitAbility()
        source[Body].body.let {
            it.applyLinearImpulse(
                source.position.cpy().sub(spec.getTarget<AbilityTargeting.Location>().position).nor().scl(-1F),
                Zero,
                true
            )
            it.type = KinematicBody // TODO use layers here instad
            it.fixtureList.forEach { it.isSensor = true }
        }

        world.system<SoundSystem>().playSoundAtPosition(
            Assets.get<SoundCue>("character/orc_elite_swoosh_sound_cue"),
            source.position
        )

        world.system<SoundSystem>().playSoundAtPosition(
            Assets.get<SoundCue>("character/orc_elite_big_shout_cue"),
            source.position
        )

        world.system<AnimationSetSystem>().setAnimation(source, "orc_elite_spin_attack")?.apply {
            onNotify("attack_hit") {
                val enemies = getUnitsWithin(source, 1.0F)
                    .filter { it[Team].teamId != source[Team].teamId }

                val strength = source[Attributes].getAttributes<CharacterAttributeSet>().strength.currentValue

                if (enemies.isNotEmpty()) {
                    world.system<SoundSystem>().playSoundAtPosition(
                        Assets.get<SoundCue>("sounds/melee_hit_sound_cue"),
                        source.position,
                        pitch = 0.5F,
                        volume = 1.0F + (enemies.size.coerceAtMost(5) / 10F)
                    )
                }

                enemies.forEach { target ->
                    val damageEffect = GameplayEffectSpec(reference("ability/damage"))
                    damageEffect.setSetByCallerMagnitude(Data.Damage, -strength * 1.5F)
                    damageEffect.addDynamicTag(Damage.Physical)
                    target[Abilities].applyGameplayEffect(source, target, damageEffect)

                    val stunEffect = GameplayEffectSpec(reference("ability/stun"))
                    stunEffect.setSetByCallerMagnitude(Data.Duration, 1.0F)
                    target[Abilities].applyGameplayEffect(source, target, stunEffect)

                    val knockbackEffect = GameplayEffectSpec(reference("ability/knockback"))
                    knockbackEffect.setSetByCallerMagnitude(Data.Knockback, .3F)
                    target[Abilities].applyGameplayEffect(source, target, knockbackEffect)
                }
            }

            onFinish {
                endAbility()
            }
        } ?: endAbility()
    }

    context(world: World, spec: AbilitySpec)
    override fun finish(cancelled: Boolean) {
        spec.entity[Body].body.apply {
            fixtureList.forEach { it.isSensor = false }
            type = DynamicBody
        }
    }
}
