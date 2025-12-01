package dev.wildware.udea.assets

import com.badlogic.gdx.audio.Sound
import dev.wildware.udea.gameManager
import ktx.assets.getAsset
import kotlin.String
import kotlin.getValue
import kotlin.lazy

/**
 * Early version of [SoundCue], can play a random sound.
 * */
data class SoundCue(
    val sounds: List<String>,
    val pitchVariance: Float = 0.0F,
    val volume: Float = 1.0F,
) : Asset<SoundCue>() {
    val soundAssets by lazy {
        sounds.map {
            gameManager.assetManager.getAsset<Sound>(it)
        }
    }
}
