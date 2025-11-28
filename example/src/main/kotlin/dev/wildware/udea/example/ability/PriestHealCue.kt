package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.GameplayEffectCue
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.example.assets.Effect
import dev.wildware.udea.example.spawnEffect

object PriestHealCue : GameplayEffectCue {
    context(world: World)
    override fun onGameplayEffectApplied(
        source: Entity,
        target: Entity,
        spec: GameplayEffectSpec
    ) {
        spawnEffect(Assets.get<Effect>("effects/heal_effect"), target)
    }
}
