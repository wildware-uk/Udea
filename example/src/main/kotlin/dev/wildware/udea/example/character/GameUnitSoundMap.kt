package dev.wildware.udea.example.character

import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.AudioMap
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.dsl.CreateDsl

@CreateDsl
data class GameUnitSoundMap(
    val attack: AssetReference<SoundCue>? = null,
    val hit: AssetReference<SoundCue>? = null,
    val death: AssetReference<SoundCue>? = null,
) : AudioMap
