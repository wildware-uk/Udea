package dev.wildware.udea.ecs.system

import com.badlogic.gdx.math.Vector3
import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.udea.Vector2
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.gameScreen
import kotlin.random.Random

class SoundSystem : IntervalSystem() {
    override fun onTick() {}

    var audioFalloff = 10F

    fun playSoundAtPosition(
        sound: AssetReference<SoundCue>,
        position: Vector2,
        pitch: Float = 1.0F,
        volume: Float = 1.0F
    ) {
        val sound = sound.value
        val position3 = Vector3(position, 0F)
        val cameraPosition = gameScreen.camera.position

        val audioDistance = position3.dst(cameraPosition)
        val volume = (1F - audioDistance / audioFalloff).coerceIn(0F, 1F)
        val pitch = (pitch + (Random.nextFloat() * sound.pitchVariance - sound.pitchVariance / 2F)).coerceIn(0.5F, 2.0F)
        val pan = ((position.x - cameraPosition.x) / 10F).coerceIn(-1F, 1F)

        sound.soundAssets.random().play(volume, pitch, pan)
    }
}
