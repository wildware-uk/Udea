package dev.wildware.udea.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World

interface GameplayEffectCue {
    context(world: World)
    fun onGameplayEffectApplied(
        source: Entity,
        target: Entity,
        gameplayEffect: GameplayEffect
    )
}
