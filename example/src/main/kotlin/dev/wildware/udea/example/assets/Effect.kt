package dev.wildware.udea.example.assets

import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.SpriteAnimationSet

data class Effect(
    val animationSet: AssetReference<SpriteAnimationSet>,
    val animation: String,
    val duration: Float = 1.0F
) : Asset<Effect>()
