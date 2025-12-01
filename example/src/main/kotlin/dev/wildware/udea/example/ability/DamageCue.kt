package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.GameplayEffectCue
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.ecs.component.animation.AnimationMapHolder
import dev.wildware.udea.ecs.component.audio.AudioMapHolder
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.ecs.system.SoundSystem
import dev.wildware.udea.example.character.GameUnitAnimationMap
import dev.wildware.udea.example.character.GameUnitSoundMap
import dev.wildware.udea.get
import dev.wildware.udea.getOrNull
import dev.wildware.udea.position

object DamageCue : GameplayEffectCue {
    context(world: World)
    override fun onGameplayEffectApplied(
        source: Entity,
        target: Entity,
        spec: GameplayEffectSpec
    ) {
        val hitAnimation = target[AnimationMapHolder].animationMap<GameUnitAnimationMap>().hit
        world.system<AnimationSetSystem>().setAnimation(target, hitAnimation)

        target.getOrNull(AudioMapHolder)?.get<GameUnitSoundMap>()?.hit?.value?.let {
            world.system<SoundSystem>().playSoundAtPosition(it, target.position)
        }
    }
}
