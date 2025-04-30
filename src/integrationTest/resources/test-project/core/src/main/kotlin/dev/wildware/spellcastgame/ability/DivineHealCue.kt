package dev.wildware.spellcastgame.ability

import com.badlogic.gdx.graphics.g2d.ParticleEffect
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ability.GameplayEffect
import dev.wildware.ability.GameplayEffectCue
import dev.wildware.ecs.component.Particles
import ktx.assets.toInternalFile

object DivineHealCue : GameplayEffectCue {
    val particleEffect = ParticleEffect().apply {
        load(
            "particles/divine/divine_hit.p".toInternalFile(),
            "particles/divine".toInternalFile()
        )
        scaleEffect(0.05F)
    }

    override fun World.onGameplayEffectApplied(source: Entity, target: Entity, gameplayEffect: GameplayEffect) {
        target[Particles].particleEffects += ParticleEffect(particleEffect).apply {
            start()
        }
    }
}
