package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.GameplayEffect
import dev.wildware.udea.ability.GameplayEffectCue
import dev.wildware.udea.ecs.component.animation.AnimationMapHolder
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.example.character.NPCAnimationMap
import dev.wildware.udea.example.component.NPC
import dev.wildware.udea.get

object MeleeHitCue : GameplayEffectCue {
    context(world: World)
    override fun onGameplayEffectApplied(
        source: Entity,
        target: Entity,
        gameplayEffect: GameplayEffect
    ) {
        val hitAnimation = target[AnimationMapHolder].animationMap<NPCAnimationMap>().hit
        world.system<AnimationSetSystem>().setAnimation(target, hitAnimation)
    }
}
