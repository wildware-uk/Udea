package dev.wildware.udea.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World

interface GameplayEffectCue {
    fun World.onGameplayEffectApplied(source: Entity, target: Entity, gameplayEffect: GameplayEffect)
}
