package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.GameplayEffectCue
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.ecs.system.SoundSystem
import dev.wildware.udea.position

object MeleeDamageCue : GameplayEffectCue {

    val meleeSoundCue = Assets.get<SoundCue>("sounds/melee_hit_sound_cue")

    context(world: World)
    override fun onGameplayEffectApplied(
        source: Entity,
        target: Entity,
        spec: GameplayEffectSpec
    ) {
        world.system<SoundSystem>().playSoundAtPosition(meleeSoundCue, target.position)
    }
}
