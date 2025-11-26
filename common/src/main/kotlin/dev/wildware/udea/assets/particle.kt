package dev.wildware.udea.assets

import com.badlogic.gdx.graphics.g2d.ParticleEffect
import dev.wildware.udea.gameScreen

/**
 * A particle effect asset.
 * Fields are for use with LibGDX particle system.
 * */
data class Particle(
    /**
     * Path relative to the `assets` folder, for the particle effects (*.p)
     * */
    val effectFile: String,

    /**
     * Path relative to the `assets` folder, of the directory containing the images for the particle effect.
     * */
    val imagesDir: String,

    /**
     * The amount to scale the particle effect by.
     * */
    val scale: Float = 1.0F
) : Asset() {

    /**
     * Loads the GDX particle effect.
     * */
    fun load(): ParticleEffect {
        val particleEffect = ParticleEffect(gameScreen.gameManager.assetManager.get(effectFile))
        particleEffect.scaleEffect(scale)
        return particleEffect
    }
}
